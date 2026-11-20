package com.healthscience.rag.ai;

import com.healthscience.rag.ai.model.RetrievedChunk;

import java.util.List;

/**
 * 重排能力接口（框架无关，见架构约束 C1/C2）。
 *
 * <p><b>重要：rerank 已退出主链路（ADR-018）</b>。
 * 原因：rerank 位于「RRF 融合之后、生成之前」，其耗时<b>全额计入首字延迟</b>；
 * 而生成式模型（qwen2.5:7b 一类）为 10 个候选打分在本地 4060 上需 3~8 秒，
 * 与 NFR「首字延迟 ≤ 3 秒」直接冲突。
 *
 * <p>因此本接口仅在 {@code retrieveMode = hybrid_rerank} 的<b>消融实验组</b>启用，
 * 用于支撑论文中「开/关 rerank」的对比数据。
 */
public interface RerankService {

    /**
     * 对候选片段重排，返回得分最高的前 topN 条。
     *
     * @param query      用户提问
     * @param candidates 融合后的候选片段
     * @param topN       保留条数
     * @return 重排后的片段；实现失败时应返回入参原序的前 topN 条（降级而非抛错）
     */
    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN);
}
