package com.healthscience.rag.ai.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.healthscience.rag.ai.RerankService;
import com.healthscience.rag.ai.RetrievalService;
import com.healthscience.rag.ai.model.KeywordHit;
import com.healthscience.rag.ai.model.RetrievalResult;
import com.healthscience.rag.ai.model.RetrievedChunk;
import com.healthscience.rag.ai.model.RetrieveMode;
import com.healthscience.rag.ai.model.VectorHit;
import com.healthscience.rag.entity.KbDocument;
import com.healthscience.rag.mapper.KbChunkMapper;
import com.healthscience.rag.mapper.KbDocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.healthscience.rag.ai.EmbeddingService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 混合检索实现（M7 引入，M9.5/B-9 起落点为 PostgreSQL 单库，ADR-023）。
 *
 * <p><b>两路召回</b>：
 * <ol>
 *   <li>向量路：{@code kb_chunk.embedding VECTOR(1024)} + HNSW 余弦距离，
 *       SQL 一次带回溯源元数据；支持按分类过滤（SimpleVectorStore 时代做不到）</li>
 *   <li>关键词路：{@code tsv} 生成列（zhparser 词级分词）+ GIN，
 *       {@code ts_rank_cd} 给出词级相关性得分，替代 V1 的 ngram 机械切分</li>
 * </ol>
 *
 * <p><b>为什么 RRF 融合仍在应用层（D10 裁决）</b>：两路分数体系不可比
 * （余弦相似度 vs ts_rank_cd），只用名次的 RRF 无需归一化；
 * 保持 Java 层融合也让三种检索策略的消融语义与 V1 完全可比。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridRetrievalService implements RetrievalService {

    /** RRF 平滑常数：越大则名次靠前的优势越弱，60 是业界常用值 */
    private static final int RRF_K = 60;

    /** 关键词路的候选池大小：给融合留足素材，再按 RRF 取 topK */
    private static final int KEYWORD_CANDIDATES = 20;

    /** B-27 冲突判定双档阈值（07 §2.2，MVP 向量相似度；可在评测集上跑 PR 曲线校准） */
    @org.springframework.beans.factory.annotation.Value("${health.rag.user-kb.conflict-high-threshold:0.90}")
    private double conflictHighThreshold;

    @org.springframework.beans.factory.annotation.Value("${health.rag.user-kb.conflict-mid-threshold:0.75}")
    private double conflictMidThreshold;

    /**
     * D5（测试报告 §21.3）：合并检索时个人块保底条数。0 = 关闭（消融实验可用）。
     * 背景：个人块与全局库同池竞争，语义被"多主题混块"平均后常被挤到候选池外。
     */
    @org.springframework.beans.factory.annotation.Value("${health.rag.user-kb.personal-guarantee-hits:2}")
    private int personalGuaranteeHits;

    /** D5/D6：个人块进入保底的最低余弦；低于此认为与问题无关，不强行塞入上下文。
     *  0.60 实测依据见 application.yml 注释（区分 0.5848 误召与 0.6094 真命中）。 */
    @org.springframework.beans.factory.annotation.Value("${health.rag.user-kb.personal-guarantee-score:0.60}")
    private double personalGuaranteeScore;

    /** 查询向量：经框架无关的 EmbeddingService（C1/C2） */
    private final EmbeddingService embeddingService;
    /** 重排组件 provider 非 dashscope 时不装配；预算耗尽/失败由其内部降级为原序（B-15） */
    private final org.springframework.beans.factory.ObjectProvider<RerankService> rerankProvider;
    private final KbChunkMapper chunkMapper;
    private final KbDocumentMapper documentMapper;

    @Override
    public RetrievalResult retrieve(String query, String retrieveMode, int topK,
                                    String scopeFilter, Long ownerUserId) {
        if (query == null || query.isBlank() || topK <= 0) {
            return RetrievalResult.empty();
        }
        String scope = scopeFilter == null || scopeFilter.isBlank() ? "global" : scopeFilter;
        List<RetrievedChunk> vectorHits = vectorSearch(query, topK * 2, scope, ownerUserId);
        // 向量路原始余弦分数：SQL 按 cosine 降序返回，首个即 top1（置信度拒答的判定依据）
        double vectorTopScore = vectorHits.isEmpty() ? 0.0 : vectorHits.get(0).score();
        Map<String, Object> trace = new java.util.LinkedHashMap<>();
        trace.put("mode", retrieveMode);
        trace.put("kbScope", scope);
        trace.put("vectorHits", toTraceHits(vectorHits));
        boolean rerankApplied = false;

        boolean hybrid = RetrieveMode.HYBRID.equals(retrieveMode)
                || RetrieveMode.HYBRID_RERANK.equals(retrieveMode);
        if (!hybrid) {
            trace.put("fused", toTraceHits(limit(vectorHits, topK)));
            trace.put("rerankApplied", false);
            return new RetrievalResult(limit(vectorHits, topK), vectorTopScore, trace, null);
        }

        List<KeywordHit> keywordHits;
        try {
            keywordHits = keywordSearch(query, scope, ownerUserId, KEYWORD_CANDIDATES);
        } catch (Exception e) {
            // 关键词路失败不应让整个检索挂掉 —— 退回纯向量
            log.warn("[Retrieval] 关键词召回失败，退回纯向量: {}", e.getMessage());
            trace.put("keywordFailed", e.getMessage());
            trace.put("fused", toTraceHits(limit(vectorHits, topK)));
            trace.put("rerankApplied", false);
            return new RetrievalResult(limit(vectorHits, topK), vectorTopScore, trace, null);
        }
        trace.put("keywordHits", keywordHits.stream()
                .map(k -> Map.of("chunkId", k.id(), "score", k.score()))
                .toList());
        log.debug("[Retrieval] 向量 {} 条 / 关键词 {} 条 → RRF 融合", vectorHits.size(), keywordHits.size());
        List<RetrievedChunk> fused = fuse(vectorHits, keywordHits);

        // B-15：仅 hybrid_rerank 策略重排（ADR-018，不进主链路）。
        // 开关/token 预算/失败降级全部收敛在 RerankService 内部，这里只管调用
        if (RetrieveMode.HYBRID_RERANK.equals(retrieveMode)) {
            RerankService reranker = rerankProvider.getIfAvailable();
            if (reranker != null) {
                fused = reranker.rerank(query, fused, fused.size());
                rerankApplied = true;
            }
        }
        trace.put("fused", toTraceHits(fused));
        trace.put("rerankApplied", rerankApplied);

        List<RetrievedChunk> limited = limit(fused, topK);
        // D5：个人资料保底 —— 把"与问题相关、但被全局库挤掉"的个人块强制放回结果
        List<RetrievedChunk> guaranteed = personalGuarantee(query, scope, ownerUserId, limited);
        if (!guaranteed.isEmpty()) {
            limited = mergeGuaranteed(guaranteed, limited, topK);
            trace.put("personalGuarantee", toTraceHits(guaranteed));
        }
        // B-27 冲突判定：仅 health 会话（对照解答只在此触发，07 §2.2）且两库块都进入 topK
        Map<String, Object> conflict = detectConflict(limited, scope, ownerUserId);
        if (conflict != null) {
            trace.put("conflict", conflict);
        }
        return new RetrievalResult(limited, vectorTopScore, trace, conflict);
    }

    /**
     * D5 个人资料保底：合并检索（scope=all）且已登录时，**单独**按余弦取个人块 ——
     * 不受「向量候选池 = topK×2」限制（实测个人块在「血压」类问题上排第 9~17 名，
     * 排 17 时连候选池都进不去，靠调融合权重救不回来）。
     *
     * <p>返回余弦 ≥ 门槛、且尚未出现在结果里的个人块，最多 hits 条；
     * 门槛的作用是防止把与当前问题无关的个人资料硬塞进 Prompt。
     */
    private List<RetrievedChunk> personalGuarantee(String query, String scope, Long ownerUserId,
                                                   List<RetrievedChunk> limited) {
        if (personalGuaranteeHits <= 0 || ownerUserId == null || !"all".equals(scope)) {
            return List.of();
        }
        List<RetrievedChunk> personalHits;
        try {
            // 多取一倍，供门槛过滤后仍能凑够条数
            personalHits = vectorSearch(query, personalGuaranteeHits * 2, "personal", ownerUserId);
        } catch (Exception e) {
            log.warn("[Retrieval] 个人块保底召回失败，忽略: {}", e.getMessage());
            return List.of();
        }
        java.util.Set<Long> present = limited.stream()
                .map(RetrievedChunk::chunkId).collect(Collectors.toSet());
        return personalHits.stream()
                .filter(c -> c.score() >= personalGuaranteeScore && !present.contains(c.chunkId()))
                .limit(personalGuaranteeHits)
                .toList();
    }

    /** 保底块置于最前（用户自己的资料优先），去重后截 topK —— 尾部被挤掉的是全局块 */
    private static List<RetrievedChunk> mergeGuaranteed(List<RetrievedChunk> guaranteed,
                                                        List<RetrievedChunk> limited, int topK) {
        List<RetrievedChunk> merged = new ArrayList<>(guaranteed.size() + limited.size());
        merged.addAll(guaranteed);
        merged.addAll(limited);
        return limit(merged, topK);
    }

    /**
     * B-27 冲突判定（双档阈值，07 §2.2）：用户库最高位块 U 与基础库最高位块 G 的余弦：
     * ≥0.90 高度相似 / 0.75~0.90 同题不同表述 → 双答案；&lt;0.75 不冲突。
     * 阈值可配（health.rag.user-kb.conflict-*-threshold）。
     */
    private Map<String, Object> detectConflict(List<RetrievedChunk> limited, String scope, Long ownerUserId) {
        if (ownerUserId == null || !("all".equals(scope))) {
            return null;   // 仅登录用户 + 合并检索才可能冲突
        }
        RetrievedChunk topUser = null;
        RetrievedChunk topBase = null;
        for (RetrievedChunk c : limited) {
            String cs = c.meta("scope");
            if ("personal".equals(cs) && topUser == null) {
                topUser = c;
            } else if ("global".equals(cs) && topBase == null) {
                topBase = c;
            }
        }
        if (topUser == null || topBase == null) {
            return null;
        }
        Double similarity = chunkMapper.selectChunkSimilarity(topUser.chunkId(), topBase.chunkId());
        if (similarity == null || similarity < conflictMidThreshold) {
            return null;
        }
        String verdict = similarity >= conflictHighThreshold ? "HIGH_SIMILARITY" : "SIMILAR_TOPIC";
        Map<String, Object> conflict = new java.util.LinkedHashMap<>();
        conflict.put("similarity", Math.round(similarity * 10000.0) / 10000.0);
        conflict.put("verdict", verdict);
        conflict.put("userChunkId", topUser.chunkId());
        conflict.put("baseChunkId", topBase.chunkId());
        conflict.put("userDoc", topUser.meta("title"));
        conflict.put("baseDoc", topBase.meta("title"));
        return conflict;
    }

    /** trace 命中条目：元数据级（chunkId/score/title/section），不含正文 */
    private List<Map<String, Object>> toTraceHits(List<RetrievedChunk> chunks) {
        List<Map<String, Object>> out = new ArrayList<>(chunks.size());
        for (RetrievedChunk c : chunks) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("chunkId", c.chunkId());
            m.put("score", c.score());
            String title = c.meta("title");
            if (!title.isEmpty()) {
                m.put("title", title);
            }
            String section = c.meta("section");
            if (!section.isEmpty()) {
                m.put("section", section);
            }
            out.add(m);
        }
        return out;
    }

    /**
     * 就绪状态真值查询（03 §D4）：pending（未向量化分块）=0 且库非空即就绪。
     * 取代退役的 kb_vector_meta —— 结构上不可能再出现"就绪却有块没向量"的静默失败。
     * M15：就绪语义限定为「全局库」——个人库 pending 不得拉挂全站可用性（07 §2.3 灾难 B）。
     */
    @Override
    public RetrievalResult retrieveInSession(String query, Long sessionId, int topK) {
        if (query == null || query.isBlank() || topK <= 0 || sessionId == null) {
            return RetrievalResult.empty();
        }
        String qvec = toPgVector(embeddingService.embed(query));
        List<RetrievedChunk> vectorHits = toChunks(chunkMapper.selectByVectorInSession(qvec, sessionId, topK * 2));
        double vectorTopScore = vectorHits.isEmpty() ? 0.0 : vectorHits.get(0).score();
        Map<String, Object> trace = new java.util.LinkedHashMap<>();
        trace.put("mode", "session_files");
        trace.put("sessionId", sessionId);
        trace.put("vectorHits", toTraceHits(vectorHits));
        List<KeywordHit> keywordHits;
        try {
            keywordHits = chunkMapper.selectByKeywordInSession(query.trim(), sessionId, KEYWORD_CANDIDATES);
        } catch (Exception e) {
            log.warn("[Retrieval] 文件集关键词召回失败，退回纯向量: {}", e.getMessage());
            keywordHits = List.of();
        }
        List<RetrievedChunk> fused = keywordHits.isEmpty() ? vectorHits : fuse(vectorHits, keywordHits);
        trace.put("fused", toTraceHits(fused));
        return new RetrievalResult(limit(fused, topK), vectorTopScore, trace, null);
    }

    @Override
    public boolean isReady() {
        Map<String, Object> stats = chunkMapper.selectVectorStats("global");
        return intOf(stats.get("total")) > 0 && intOf(stats.get("pending")) == 0;
    }

    private List<RetrievedChunk> vectorSearch(String query, int size, String scope, Long ownerUserId) {
        String qvec = toPgVector(embeddingService.embed(query));
        List<VectorHit> hits = chunkMapper.selectByVector(qvec, null, scope, ownerUserId, size);
        if (hits.isEmpty()) {
            return List.of();
        }
        List<RetrievedChunk> result = new ArrayList<>(hits.size());
        for (VectorHit h : hits) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("documentId", String.valueOf(h.documentId()));
            meta.put("title", h.title() == null ? "" : h.title());
            meta.put("source", h.source() == null ? "" : h.source());
            meta.put("category", h.category() == null ? "" : h.category());
            meta.put("section", h.section() == null ? "" : h.section());
            meta.put("scope", h.scope() == null ? "global" : h.scope());
            result.add(new RetrievedChunk(h.id(),
                    h.content() == null ? "" : h.content(), h.score(), meta));
        }
        return result;
    }

    private List<RetrievedChunk> toChunks(List<VectorHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<RetrievedChunk> result = new ArrayList<>(hits.size());
        for (VectorHit h : hits) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("documentId", String.valueOf(h.documentId()));
            meta.put("title", h.title() == null ? "" : h.title());
            meta.put("source", h.source() == null ? "" : h.source());
            meta.put("category", h.category() == null ? "" : h.category());
            meta.put("section", h.section() == null ? "" : h.section());
            meta.put("scope", h.scope() == null ? "global" : h.scope());
            result.add(new RetrievedChunk(h.id(),
                    h.content() == null ? "" : h.content(), h.score(), meta));
        }
        return result;
    }

    private List<KeywordHit> keywordSearch(String query, String scope, Long ownerUserId, int limit) {
        // 库内 zhparser 分词 + 词间 OR（P2-3），Java 侧只做透传
        String qtext = query.trim();
        if (qtext.isEmpty()) {
            return List.of();
        }
        return chunkMapper.selectByKeyword(qtext, scope, ownerUserId, limit);
    }

    /** RRF 融合：按名次给分，两路都命中的自然排到前面 */
    private List<RetrievedChunk> fuse(List<RetrievedChunk> vectorHits, List<KeywordHit> keywordHits) {
        Map<Long, Double> scores = new LinkedHashMap<>();
        Map<Long, RetrievedChunk> pool = new HashMap<>();

        for (int i = 0; i < vectorHits.size(); i++) {
            RetrievedChunk c = vectorHits.get(i);
            scores.merge(c.chunkId(), 1.0 / (RRF_K + i + 1), Double::sum);
            pool.putIfAbsent(c.chunkId(), c);
        }
        if (!keywordHits.isEmpty()) {
            // 关键词结果没有 title/source，需要按文档 ID 批量补一次
            Map<Long, KbDocument> docs = loadDocuments(
                    keywordHits.stream().map(KeywordHit::documentId).toList());
            for (int i = 0; i < keywordHits.size(); i++) {
                KeywordHit h = keywordHits.get(i);
                scores.merge(h.id(), 1.0 / (RRF_K + i + 1), Double::sum);
                pool.putIfAbsent(h.id(), toChunk(h, docs.get(h.documentId())));
            }
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .map(e -> rescale(pool.get(e.getKey()), e.getValue()))
                .filter(c -> c != null)
                .toList();
    }

    private Map<Long, KbDocument> loadDocuments(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<KbDocument> docs = documentMapper.selectList(
                new LambdaQueryWrapper<KbDocument>().in(KbDocument::getId, ids));
        return docs.stream().collect(Collectors.toMap(KbDocument::getId, Function.identity(), (a, b) -> a));
    }

    private static RetrievedChunk toChunk(KeywordHit hit, KbDocument doc) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentId", String.valueOf(hit.documentId()));
        meta.put("title", doc == null || doc.getTitle() == null ? "" : doc.getTitle());
        meta.put("source", doc == null || doc.getSource() == null ? "" : doc.getSource());
        meta.put("category", doc == null || doc.getCategory() == null ? "" : doc.getCategory());
        meta.put("section", hit.section() == null ? "" : hit.section());
        meta.put("scope", hit.scope() == null ? "global" : hit.scope());
        return new RetrievedChunk(hit.id(), hit.content(), 0.0, meta);
    }

    /** 用 RRF 得分替换原始得分，保证对外只有一个可比的分数 */
    private static RetrievedChunk rescale(RetrievedChunk c, double rrfScore) {
        if (c == null) {
            return null;
        }
        return new RetrievedChunk(c.chunkId(), c.content(), rrfScore, c.metadata());
    }

    private static List<RetrievedChunk> limit(List<RetrievedChunk> list, int topK) {
        return list.size() <= topK ? list : list.subList(0, topK);
    }

    /** float[] → pgvector 文本字面量 {@code [v1,v2,...]}（1024 维，JDBC String 参数 + SQL CAST） */
    private static String toPgVector(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 10 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    private static int intOf(Object v) {
        if (v == null) {
            return 0;
        }
        return v instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(v));
    }
}
