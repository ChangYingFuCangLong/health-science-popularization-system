package com.healthscience.rag.ai.impl;

import com.healthscience.rag.ai.ChatService;
import com.healthscience.rag.ai.model.ChatChunk;
import com.healthscience.rag.ai.model.ChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 对话能力的 Mock 实现（ADR-021）。
 *
 * <p><b>存在意义</b>：让前端在 M3 阶段即可联调<b>完整</b> SSE 链路
 * （{@code reference → delta × n → done}），完全不依赖 Ollama 与 DeepSeek API Key，
 * 从而消除 M4 阶段「前后端同时调试」的风险。
 *
 * <p><b>M4 之后</b>本类不删除：它仍是前端联调与单元测试的稳定桩。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "health.ai.provider", havingValue = "mock")
public class MockChatService implements ChatService {

    /** 增量片段之间的间隔，模拟真实生成的节奏（便于观察前端合帧节流效果） */
    private static final Duration DELTA_INTERVAL = Duration.ofMillis(80);

    /** 每个增量片段的字符数。逐字推送会使事件数暴涨，按片推送更接近真实实现 */
    private static final int SLICE_SIZE = 4;

    /** 刻意标注 Mock 来源，避免被误认为真实回答 */
    private static final String MOCK_REPLY = "【Mock 回答】当前为 M1 阶段的模拟流式响应，"
            + "用于前端联调 SSE 链路（reference → delta×n → done）。"
            + "真实回答将在 M4 接入 DeepSeek 后产生。";

    @Override
    public String chat(ChatRequest request) {
        log.debug("[MockChatService] 同步对话, question={}", request.question());
        return MOCK_REPLY;
    }

    @Override
    public Flux<ChatChunk> chatStream(ChatRequest request) {
        log.debug("[MockChatService] 流式对话, question={}, retry={}",
                request.question(), request.isRetry());

        // 1) reference 先推：纯对话阶段为空列表，前端逻辑与 RAG 阶段完全一致
        Flux<ChatChunk> reference = Flux.just(ChatChunk.Reference.empty());

        // 2) delta 逐片推送
        Flux<ChatChunk> deltas = Flux.fromIterable(slice(MOCK_REPLY))
                .delayElements(DELTA_INTERVAL)
                .map(ChatChunk.Delta::new);

        // 3) 不产出 Done —— 由 Controller 组装（依赖落库后的 messageId 与耗时统计）
        return Flux.concat(reference, deltas);
    }

    /** 将文本切成固定长度的片段，避免逐字推送产生过多事件 */
    private static List<String> slice(String text) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < text.length(); i += SLICE_SIZE) {
            parts.add(text.substring(i, Math.min(text.length(), i + SLICE_SIZE)));
        }
        return parts;
    }
}
