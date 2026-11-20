package com.healthscience.rag.ai;

import com.healthscience.rag.ai.model.ChatChunk;
import com.healthscience.rag.ai.model.ChatRequest;
import reactor.core.publisher.Flux;

/**
 * 对话能力接口（框架无关，见架构约束 C1/C2：业务层只依赖本接口，不引用任何 AI 框架类型）。
 *
 * <p><b>实现类演进</b>：
 * <ul>
 *   <li>M1–M3：{@link com.healthscience.rag.ai.impl.MockChatService}（ADR-021，让前端提前联调 SSE）</li>
 *   <li>M4 起：{@link com.healthscience.rag.ai.impl.DashScopeChatService}（阿里百炼，主实现）；
 *       {@code LocalChatService}（Ollama）作为云端不可用时的降级通道</li>
 * </ul>
 *
 * <p><b>三实现互斥</b>：统一由 {@code health.ai.provider} 选择（dashscope / ollama / mock），
 * 未配置时默认 dashscope。
 */
public interface ChatService {

    /**
     * 同步对话，返回完整回答。
     *
     * @param request 对话请求
     * @return 完整回答文本
     */
    String chat(ChatRequest request);

    /**
     * 流式对话，返回结构化事件流。
     *
     * <p>事件顺序约定：{@code Reference}（可空列表）→ {@code Delta} × N → 正常结束。
     * 失败时以 {@code Error} 结束流。{@code Done} 不由此接口产出，由 Controller 组装。
     *
     * @param request 对话请求
     * @return 结构化事件流
     */
    Flux<ChatChunk> chatStream(ChatRequest request);
}
