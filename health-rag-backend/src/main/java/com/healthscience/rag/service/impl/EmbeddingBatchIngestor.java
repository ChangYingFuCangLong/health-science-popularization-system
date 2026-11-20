package com.healthscience.rag.service.impl;

import com.healthscience.rag.ai.EmbeddingService;
import com.healthscience.rag.ai.PgVectorText;
import com.healthscience.rag.entity.KbChunk;
import com.healthscience.rag.mapper.KbChunkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 分块批量向量化落库组件（M12/B-19 自 KbImportServiceImpl 抽取，JSONL 导入与上传流水线共用）。
 *
 * <p><b>写入顺序铁律（P1 根治，ADR-023）</b>：先向量化、后落库 —— 分块行与它的向量
 * 在同一条 INSERT 里出现，结构上不可能出现"分块入库成功而向量静默丢失"。
 * 批量 embedding 最终失败的分块以 embedding = NULL 落库（返回 pending 计数），
 * 由管理端"重建索引"回补，索引状态随之显式变为"未就绪"。
 *
 * <p><b>节流参数</b>（V1 实测）：10 条/批 + 每批 300ms 间隔不触发云端限流；
 * 批失败退避重试 3 次，仍失败降级逐条。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingBatchIngestor {

    /** 每批向量化条数（10 条 ≈ 0.65s，实测可控区间） */
    public static final int EMBED_BATCH = 10;

    private static final int MAX_ATTEMPTS = 3;

    private final KbChunkMapper chunkMapper;
    private final EmbeddingService embeddingService;

    @Value("${health.rag.embed-delay-ms:300}")
    private long embedDelayMs;

    /**
     * 向量化并落库一批分块（调用方保证批次 ≤ {@link #EMBED_BATCH}）。
     *
     * @return [embedded, pending]：向量成功附着的条数 / 以 NULL 向量入库的条数
     */
    public int[] embedAndInsert(List<KbChunk> batch) {
        List<String> texts = batch.stream()
                .map(c -> c.getContent() == null ? "" : c.getContent())
                .toList();
        List<float[]> vectors = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                vectors = embeddingService.embedBatch(texts);
                sleepQuietly(embedDelayMs);   // 成功也节流，避免把限额一次性打满
                break;
            } catch (Exception e) {
                // 失败多为限流：退避后再试
                log.warn("[Ingest] 批量向量化失败（{} 条，第 {} 次）：{}", batch.size(), attempt, e.getMessage());
                sleepQuietly(embedDelayMs * 4L * attempt);
            }
        }

        int embedded = 0;
        int pending = 0;
        if (vectors != null && vectors.size() == batch.size()) {
            for (int i = 0; i < batch.size(); i++) {
                insertWithVector(batch.get(i), PgVectorText.of(vectors.get(i)));
                embedded++;
            }
            return new int[]{embedded, pending};
        }

        // 整批失败：降级逐条，尽量少留缺口
        log.warn("[Ingest] 整批 {} 次失败，降级逐条重试", MAX_ATTEMPTS);
        for (KbChunk chunk : batch) {
            String vector = null;
            try {
                vector = PgVectorText.of(embeddingService.embed(chunk.getContent()));
                sleepQuietly(embedDelayMs);
            } catch (Exception ex) {
                pending++;
                log.error("[Ingest] 单条向量化失败，以 NULL 向量入库（重建索引可回填）：{}", ex.getMessage());
            }
            insertWithVector(chunk, vector);
            if (vector != null) {
                embedded++;
            }
        }
        return new int[]{embedded, pending};
    }

    /** 当前 embedding 模型维度（ImportResult 展示用，内部有缓存） */
    public int dimension() {
        return embeddingService.dimension();
    }

    /**
     * 独立批量向量化（重建回填用，不落库）：带退避重试与节流。
     *
     * @return 与入参等长的向量列表；最终失败返回 null（调用方跳过该批）
     */
    public List<float[]> embedBatch(List<String> texts) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                List<float[]> vectors = embeddingService.embedBatch(texts);
                sleepQuietly(embedDelayMs);
                return vectors;
            } catch (Exception e) {
                log.warn("[Ingest] 批量向量化失败（{} 条，第 {} 次）：{}", texts.size(), attempt, e.getMessage());
                sleepQuietly(embedDelayMs * attempt);
            }
        }
        return null;
    }

    /** 分块与其向量同一条 INSERT 落库（vector 为 null 时该列省略，落库为 NULL） */
    private void insertWithVector(KbChunk chunk, String vector) {
        chunk.setEmbedding(vector);
        chunkMapper.insert(chunk);
        chunk.setEmbedding(null);   // 批内复用对象前清掉大字段，防误用
    }

    private static void sleepQuietly(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
