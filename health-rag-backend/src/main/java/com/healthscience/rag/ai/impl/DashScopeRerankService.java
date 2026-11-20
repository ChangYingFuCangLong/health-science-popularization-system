package com.healthscience.rag.ai.impl;

import com.healthscience.rag.ai.RerankService;
import com.healthscience.rag.ai.model.RetrievedChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 重排实现（DashScope {@code qwen3.7-text-rerank} REST 直连，任务卡 B-15）。
 *
 * <p><b>通道（2026-09-19 spike 钉定）</b>：与 gte-rerank 系列同端点
 * {@code POST /api/v1/services/rerank/text-rerank/text-rerank}，
 * 响应 {@code output.results[{index, relevance_score}]}，计费
 * {@code usage.total_tokens}（query + 全部候选文档）。
 *
 * <p><b>额度管理</b>：每次成功调用打一行用量日志（本次 + 累计 token），
 * 由使用者监控额度（用户裁决 2026-09-19：不做代码级预算闸，人工盯）。
 *
 * <p><b>降级纪律</b>（ADR-018 维持）：rerank 仅在 {@code hybrid_rerank} 策略下启用；
 * 超时/失败一律返回 RRF 原序前 topN，绝不影响主链路。
 * 重排只调整顺序，{@code score} 仍为 RRF 分数（分数语义单一）。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "health.ai.provider", havingValue = "dashscope")
public class DashScopeRerankService implements RerankService {

    private static final String ENDPOINT = "/api/v1/services/rerank/text-rerank/text-rerank";

    private final RestClient client;
    private final String model;
    private final boolean enabled;
    private final AtomicLong usedTokens = new AtomicLong(0);

    public DashScopeRerankService(
            @Value("${health.ai.dashscope-api-key:}") String apiKey,
            @Value("${health.ai.rerank-url:https://dashscope.aliyuncs.com}") String baseUrl,
            @Value("${health.rag.rerank-model:qwen3.7-text-rerank}") String model,
            @Value("${health.rag.rerank-enabled:true}") boolean enabled) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(3));   // ADR-018：rerank 耗时全额计入首字延迟，必须短
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
        this.model = model;
        this.enabled = enabled;
        log.info("[Rerank] 已装配：model={}, enabled={}", model, enabled);
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) {
            return candidates == null ? List.of() : candidates;
        }
        if (topN <= 0) {
            return List.of();
        }
        List<RetrievedChunk> bounded = candidates.size() > topN ? candidates.subList(0, topN) : candidates;
        if (!enabled) {
            return List.copyOf(bounded);   // 总开关关闭：原序
        }
        try {
            List<String> documents = bounded.stream()
                    .map(RetrievedChunk::content)
                    .toList();
            Map<String, Object> resp = client.post()
                    .uri(ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "model", model,
                            "input", Map.of("query", query, "documents", documents),
                            "parameters", Map.of("return_documents", false, "top_n", bounded.size())))
                    .retrieve()
                    .body(Map.class);
            return applyResults(bounded, resp);
        } catch (Exception e) {
            // 超时/限流/网络：降级原序（BizException 同样降级，rerank 失败不是故障）
            log.warn("[Rerank] 重排失败，降级 RRF 原序: {}", e.getMessage());
            return List.copyOf(bounded);
        }
    }

    /** 按 relevance_score 降序重排候选（score 保留 RRF 值），并记录本次 token 用量 */
    @SuppressWarnings("unchecked")
    private List<RetrievedChunk> applyResults(List<RetrievedChunk> bounded, Map<String, Object> resp) {
        long tokens = extractUsage(resp);
        recordUsage(tokens);
        List<Map<String, Object>> results = (List<Map<String, Object>>) ((Map<String, Object>) resp.get("output")).get("results");
        if (results == null || results.isEmpty()) {
            return List.copyOf(bounded);
        }
        List<RetrievedChunk> reranked = new ArrayList<>(bounded.size());
        for (Map<String, Object> r : results) {
            int index = ((Number) r.get("index")).intValue();
            if (index >= 0 && index < bounded.size()) {
                reranked.add(bounded.get(index));
            }
        }
        // 结果缺失的候选按原序补在尾部，保证不丢引用
        for (RetrievedChunk c : bounded) {
            if (!reranked.contains(c)) {
                reranked.add(c);
            }
        }
        return List.copyOf(reranked);
    }

    private long extractUsage(Map<String, Object> resp) {
        try {
            Map<String, Object> usage = (Map<String, Object>) resp.get("usage");
            return ((Number) usage.get("total_tokens")).longValue();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 累计 token 用量；每次成功调用打一行，供人工监控额度 */
    void recordUsage(long tokens) {
        long total = usedTokens.addAndGet(tokens);
        log.info("[Rerank] 本次 {} tokens，累计 {}", tokens, total);
    }

    /** 已累计消耗的 token 数（监控/测试用） */
    java.util.concurrent.atomic.AtomicLong usedTokens() {
        return usedTokens;
    }
}
