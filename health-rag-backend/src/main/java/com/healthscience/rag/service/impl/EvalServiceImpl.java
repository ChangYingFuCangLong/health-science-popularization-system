package com.healthscience.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.healthscience.rag.ai.EvalScoringService;
import com.healthscience.rag.ai.RetrievalService;
import com.healthscience.rag.ai.model.RetrievedChunk;
import com.healthscience.rag.ai.model.RetrieveMode;
import com.healthscience.rag.common.exception.BizException;
import com.healthscience.rag.common.result.PageResult;
import com.healthscience.rag.entity.EvalDataset;
import com.healthscience.rag.entity.EvalResult;
import com.healthscience.rag.mapper.AdminMapper;
import com.healthscience.rag.mapper.EvalDatasetMapper;
import com.healthscience.rag.mapper.EvalResultMapper;
import com.healthscience.rag.model.dto.AdminDTO;
import com.healthscience.rag.service.EvalService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 评测与实验实现（02 §八 · B-22 闭环）。
 *
 * <p><b>一条批次完整走过的四步</b>：
 * <ol>
 *   <li>逐题逐策略检索 → 算命中名次 → 落 {@code eval_result}（检索侧指标）；</li>
 *   <li>{@code generate=true} 时基于<b>该策略检索到的块</b>生成答案（不是重新检索，
 *       否则消融矩阵里「策略」这一维就失效了）；</li>
 *   <li>批次收尾跑 LLM-as-judge，成功则写分数 + {@code score_source='AUTO'}；</li>
 *   <li>人工复核接口覆盖分数并置 {@code 'MANUAL'}。</li>
 * </ol>
 *
 * <p><b>失败隔离</b>：单题的检索、生成、判分任一失败都只影响该条，
 * 绝不让整批实验崩掉——跑一次 60 题 × 4 策略成本很高，不能因个别题重跑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalServiceImpl implements EvalService {

    private static final DateTimeFormatter BATCH_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static final BigDecimal FAITH_MIN = BigDecimal.ONE;
    private static final BigDecimal FAITH_MAX = new BigDecimal("5");

    private final EvalDatasetMapper datasetMapper;
    private final EvalResultMapper resultMapper;
    private final RetrievalService retrievalService;
    private final EvalScoringService scoringService;
    private final AdminMapper adminMapper;
    private final ObjectMapper objectMapper;

    @Override
    public PageResult<AdminDTO.EvalItemVO> dataset(String category, int pageNum, int pageSize) {
        Page<EvalDataset> page = new Page<>(Math.max(pageNum, 1), Math.min(Math.max(pageSize, 1), 100));
        page.addOrder(OrderItem.desc("id"));
        LambdaQueryWrapper<EvalDataset> q = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(category)) {
            q.eq(EvalDataset::getCategory, category.trim());
        }
        return PageResult.of(datasetMapper.selectPage(page, q), this::toItemVO);
    }

    @Override
    public Long addDataset(String question, String groundTruth, List<Long> refChunkIds,
                           String category, Integer difficulty) {
        if (!StringUtils.hasText(question)) {
            throw BizException.badRequest("评测问题不能为空");
        }
        EvalDataset d = new EvalDataset();
        d.setQuestion(question.trim());
        d.setGroundTruth(groundTruth);
        d.setRefChunkIds(refChunkIds == null || refChunkIds.isEmpty()
                ? null : writeJson(refChunkIds));
        d.setCategory(category);
        d.setDifficulty(difficulty == null ? EvalDataset.DIFFICULTY_MEDIUM : difficulty);
        datasetMapper.insert(d);
        return d.getId();
    }

    @Override
    public String runExperiment(List<String> strategies, List<Long> datasetIds, boolean generate) {
        if (strategies == null || strategies.isEmpty()) {
            throw BizException.badRequest("至少指定一个策略");
        }
        for (String s : strategies) {
            if (!RetrieveMode.NAIVE.equals(s)
                    && !RetrieveMode.HYBRID.equals(s)
                    && !RetrieveMode.HYBRID_RERANK.equals(s)
                    && !RetrieveMode.NONE.equals(s)) {
                throw BizException.badRequest("未知检索策略: " + s);
            }
        }
        List<EvalDataset> items;
        if (datasetIds == null || datasetIds.isEmpty()) {
            items = datasetMapper.selectList(
                    new LambdaQueryWrapper<EvalDataset>().orderByAsc(EvalDataset::getId));
        } else {
            items = datasetMapper.selectBatchIds(datasetIds);
        }
        if (items.isEmpty()) {
            throw BizException.badRequest("没有可执行的评测题，请先录入");
        }

        String batchNo = LocalDateTime.now().format(BATCH_FMT);
        int written = 0;
        List<PendingJudge> pending = new ArrayList<>();
        for (String strategy : strategies) {
            for (EvalDataset item : items) {
                RunOutcome outcome = runOne(batchNo, strategy, item, generate);
                written += 1;
                if (outcome != null && outcome.answer() != null) {
                    pending.add(new PendingJudge(outcome.resultId(), item, outcome.hits(), outcome.answer()));
                }
            }
        }

        int judgedOk = 0;
        int judgedFail = 0;
        if (!pending.isEmpty()) {
            log.info("[Eval] 批次 {} 开始自动判分，待评 {} 条", batchNo, pending.size());
            for (PendingJudge p : pending) {
                EvalScoringService.JudgeResult jr = scoringService.judge(
                        p.item().getQuestion(), p.item().getGroundTruth(), p.answer(), p.hits());
                if (!jr.ok()) {
                    judgedFail++;
                    log.warn("[Eval] 判分跳过 resultId={} datasetId={} 原因={}",
                            p.resultId(), p.item().getId(), jr.reason());
                    continue;
                }
                EvalResult upd = new EvalResult();
                upd.setId(p.resultId());
                upd.setFaithfulness(jr.faithfulness());
                upd.setCitationCorrect(BigDecimal.valueOf(jr.citationCorrect()));
                upd.setScoreSource(EvalResult.SOURCE_AUTO);
                resultMapper.updateById(upd);
                judgedOk++;
            }
        }

        log.info("[Eval] 批次 {} 完成：{} 策略 × {} 题 = {} 条；生成 {} 条，判分成功 {} 条、跳过 {} 条",
                batchNo, strategies.size(), items.size(), written, pending.size(), judgedOk, judgedFail);
        return batchNo;
    }

    /** 单题单策略：检索 → 命中名次 →（可选）生成 → 落库 */
    private RunOutcome runOne(String batchNo, String strategy, EvalDataset item, boolean generate) {
        long startAt = System.currentTimeMillis();
        List<RetrievedChunk> hits;
        if (RetrieveMode.NONE.equals(strategy)) {
            // 消融基线 none = 纯对话、不检索，与 RagChatService 的 NONE 分支保持同一语义。
            // 不能在 HybridRetrievalService 里判 NONE：该服务对一切非 hybrid 模式统一走向量路
            // （历史上把 none 误当作 naive，导致两者 HitRate/MRR 完全相同，消融失去对照）。
            // 也不宜把「不检索」判定散落到检索服务，否则与 chat 链路的 NONE 短路重复。
            hits = List.of();
        } else {
            try {
                // 评测恒跑全局库（07/08：个人库与画像不参与实验，保消融可比性）
                hits = retrievalService.retrieve(item.getQuestion(), strategy, EVAL_TOP_K,
                        "global", null).chunks();
            } catch (Exception e) {
                log.warn("[Eval] 检索失败，按未命中记录: strategy={}, datasetId={}, err={}",
                        strategy, item.getId(), e.getMessage());
                hits = List.of();
            }
        }

        Set<Long> refs = parseRefIds(item.getRefChunkIds());
        Integer hitRank = null;
        for (int i = 0; i < hits.size(); i++) {
            if (refs.contains(hits.get(i).chunkId())) {
                hitRank = i + 1;
                break;
            }
        }
        boolean isHit = hitRank != null;

        // 生成必须基于本策略检索到的块，否则「策略」这一消融维度失去意义
        String answer = generate ? scoringService.generate(item.getQuestion(), hits) : null;
        int latencyMs = (int) (System.currentTimeMillis() - startAt);

        EvalResult r = new EvalResult();
        r.setBatchNo(batchNo);
        r.setDatasetId(item.getId());
        r.setStrategy(strategy);
        r.setRetrievedJson(writeJson(toRetrieved(hits)));
        r.setHitRank(hitRank);
        r.setIsHit(isHit);
        r.setAnswer(answer);
        r.setLatencyMs(latencyMs);
        resultMapper.insert(r);
        return new RunOutcome(r.getId(), hits, answer);
    }

    @Override
    public PageResult<AdminDTO.EvalResultVO> results(String batchNo, String strategy,
                                                     int pageNum, int pageSize) {
        List<Map<String, Object>> rows = adminMapper.selectEvalResults(batchNo, strategy);
        List<AdminDTO.EvalResultVO> vos = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            vos.add(new AdminDTO.EvalResultVO(
                    longOf(r.get("id")), str(r.get("batchNo")), longOf(r.get("datasetId")),
                    str(r.get("question")), str(r.get("strategy")),
                    // 契约规定未命中为 null，不能转成 0（0 会被误读成"排在第 0 位"）
                    r.get("hitRank") == null ? null : intOf(r.get("hitRank")),
                    intOf(r.get("isHit")),
                    parseRetrieved(str(r.get("retrievedJson"))),
                    str(r.get("answer")),
                    decimalOf(r.get("faithfulness")), decimalOf(r.get("citationCorrect")),
                    str(r.get("scoreSource")),
                    intOf(r.get("latencyMs"))));
        }
        int size = Math.min(Math.max(pageSize, 1), 100);
        return PageResult.of(vos, Math.max(pageNum, 1), size);
    }

    @Override
    public void review(Long resultId, BigDecimal faithfulness, Integer citationCorrect) {
        if (resultId == null) {
            throw BizException.badRequest("结果 ID 不能为空");
        }
        if (faithfulness == null && citationCorrect == null) {
            throw BizException.badRequest("至少提供一个分数（faithfulness 或 citationCorrect）");
        }
        if (faithfulness != null
                && (faithfulness.compareTo(FAITH_MIN) < 0 || faithfulness.compareTo(FAITH_MAX) > 0)) {
            throw BizException.badRequest("faithfulness 必须在 1~5 之间");
        }
        if (citationCorrect != null && citationCorrect != 0 && citationCorrect != 1) {
            throw BizException.badRequest("citationCorrect 只能为 0 或 1");
        }
        EvalResult exist = resultMapper.selectById(resultId);
        if (exist == null) {
            throw BizException.notFound("评测结果不存在");
        }
        EvalResult upd = new EvalResult();
        upd.setId(resultId);
        if (faithfulness != null) {
            upd.setFaithfulness(faithfulness.setScale(2, RoundingMode.HALF_UP));
        }
        if (citationCorrect != null) {
            upd.setCitationCorrect(BigDecimal.valueOf(citationCorrect));
        }
        upd.setScoreSource(EvalResult.SOURCE_MANUAL);
        resultMapper.updateById(upd);
        log.info("[Eval] 人工复核 resultId={} faithfulness={} citationCorrect={}",
                resultId, faithfulness, citationCorrect);
    }

    @Override
    public AdminDTO.EvalSummaryVO summary(String batchNo) {
        String no = StringUtils.hasText(batchNo) ? batchNo : resultMapper.selectLatestBatchNo();
        if (!StringUtils.hasText(no)) {
            return new AdminDTO.EvalSummaryVO(null, List.of());
        }
        List<AdminDTO.EvalSummaryItemVO> items = new ArrayList<>();
        for (Map<String, Object> row : adminMapper.selectEvalSummary(no)) {
            items.add(new AdminDTO.EvalSummaryItemVO(
                    str(row.get("strategy")),
                    doubleOf(row.get("hitRate5")),
                    doubleOf(row.get("mrr")),
                    doubleOf(row.get("avgFaithfulness")),
                    intOf(row.get("avgLatencyMs")),
                    intOf(row.get("sampleCount"))));
        }
        return new AdminDTO.EvalSummaryVO(no, items);
    }

    // ---------- 内部载体 ----------

    /** 单题执行结果：带上 hits 与 answer 供判分阶段使用，避免二次查库反序列化 */
    private record RunOutcome(Long resultId, List<RetrievedChunk> hits, String answer) {
    }

    /** 待判分项 */
    private record PendingJudge(Long resultId, EvalDataset item,
                                List<RetrievedChunk> hits, String answer) {
    }

    // ---------- 工具 ----------

    private AdminDTO.EvalItemVO toItemVO(EvalDataset d) {
        return new AdminDTO.EvalItemVO(
                d.getId(), d.getQuestion(), d.getGroundTruth(),
                parseRefIds(d.getRefChunkIds()).isEmpty()
                        ? List.of() : new ArrayList<>(parseRefIds(d.getRefChunkIds())),
                d.getCategory(), d.getDifficulty(), d.getCreateTime());
    }

    private static List<Map<String, Object>> toRetrieved(List<RetrievedChunk> hits) {
        List<Map<String, Object>> list = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            RetrievedChunk c = hits.get(i);
            list.add(Map.of(
                    "chunkId", c.chunkId(),
                    "rank", i + 1,
                    "score", c.score()));
        }
        return list;
    }

    /** 把 retrieved_json 的文本解析成数组；解析失败返回空数组而不是抛异常（历史脏数据不阻断查询） */
    private List<Map<String, Object>> parseRetrieved(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() { });
        } catch (Exception e) {
            log.warn("[Eval] retrieved_json 解析失败，按空数组返回");
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Set<Long> parseRefIds(String json) {
        Set<Long> ids = new LinkedHashSet<>();
        if (json == null || json.isBlank()) {
            return ids;
        }
        try {
            List<Object> raw = objectMapper.readValue(json, List.class);
            for (Object o : raw) {
                if (o instanceof Number n) {
                    ids.add(n.longValue());
                } else if (o != null) {
                    ids.add(Long.parseLong(String.valueOf(o)));
                }
            }
        } catch (Exception e) {
            log.warn("[Eval] ref_chunk_ids 解析失败: {}", json);
        }
        return ids;
    }

    private String writeJson(Object v) {
        try {
            return objectMapper.writeValueAsString(v);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static int intOf(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof Boolean b) {
            return b ? 1 : 0;
        }
        return v instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(v));
    }

    private static Long longOf(Object v) {
        if (v == null) {
            return null;
        }
        return v instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(v));
    }

    private static Double doubleOf(Object v) {
        if (v == null) {
            return null;
        }
        return v instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(v));
    }

    private static BigDecimal decimalOf(Object v) {
        if (v == null) {
            return null;
        }
        return v instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(v));
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
