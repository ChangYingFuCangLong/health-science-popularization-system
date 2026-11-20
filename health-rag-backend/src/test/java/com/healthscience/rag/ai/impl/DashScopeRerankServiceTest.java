package com.healthscience.rag.ai.impl;

import com.healthscience.rag.ai.model.RetrievedChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RerankService 降级纪律单测（M11/B-15）。
 *
 * <p>核心约束：rerank 绝不影响主链路 —— 开关关闭/调用失败一律返回 RRF 原序前 topN。
 * 真实重排效果走实弹验证（DashScope 实测），单测只守降级路径。
 */
class DashScopeRerankServiceTest {

    private static final String QUERY = "怎么预防高血压";

    private List<RetrievedChunk> candidates() {
        return List.of(
                new RetrievedChunk(1L, "限盐减重", 0.0322, Map.of("title", "A")),
                new RetrievedChunk(2L, "规律运动", 0.0161, Map.of("title", "B")),
                new RetrievedChunk(3L, "流感疫苗", 0.0080, Map.of("title", "C")));
    }

    @Test
    @DisplayName("开关关闭：不调 API，原序返回")
    void disabledKeepsOriginalOrder() {
        // bogus URL：若真的发起调用必然异常，从而证明未调用
        DashScopeRerankService svc = new DashScopeRerankService(
                "dummy-key", "http://127.0.0.1:1", "qwen3.7-text-rerank", false);

        assertThat(svc.rerank(QUERY, candidates(), 3))
                .extracting(RetrievedChunk::chunkId)
                .containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("调用失败（连接拒绝）：降级 RRF 原序，不抛出")
    void failureFallsBackToOriginalOrder() {
        DashScopeRerankService svc = new DashScopeRerankService(
                "dummy-key", "http://127.0.0.1:1", "qwen3.7-text-rerank", true);

        assertThat(svc.rerank(QUERY, candidates(), 3))
                .extracting(RetrievedChunk::chunkId)
                .containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("topN 截断：只返回前 topN 条")
    void topNBounds() {
        DashScopeRerankService svc = new DashScopeRerankService(
                "dummy-key", "http://127.0.0.1:1", "qwen3.7-text-rerank", false);

        assertThat(svc.rerank(QUERY, candidates(), 2)).hasSize(2);
        assertThat(svc.rerank(QUERY, candidates(), 0)).isEmpty();
    }

    @Test
    @DisplayName("累计用量计数：recordUsage 累加可见（人工监控额度依据）")
    void usageAccumulates() {
        DashScopeRerankService svc = new DashScopeRerankService(
                "dummy-key", "http://127.0.0.1:1", "qwen3.7-text-rerank", true);

        svc.recordUsage(174);
        svc.recordUsage(326);
        assertThat(svc.usedTokens()).hasValue(500);
    }
}
