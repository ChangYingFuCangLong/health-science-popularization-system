package com.healthscience.rag.ai.impl;

import com.healthscience.rag.ai.ChatService;
import com.healthscience.rag.ai.model.ChatChunk;
import com.healthscience.rag.ai.model.ChatRequest;
import com.healthscience.rag.ai.model.ChatTurn;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 对话能力的<b>阿里百炼（DashScope）</b>实现，走 OpenAI 兼容模式，M4 起作为主实现。
 *
 * <p>本类是 ai 包内<b>唯一允许依赖 Spring AI 类型的地方</b> —— 接口与业务层
 * 仍然只依赖 {@link ChatService} 与自定义 model（约束 C1/C2）。
 *
 * <h3>为什么默认关闭思考链（thinking）</h3>
 * <p>实测（2026-09-18，模型 {@code qwen3.7-flash-2026-07-15}）：该模型默认开启思考模式，
 * 流式响应会先推送大量 {@code reasoning_content} 帧，而后才是真正的 {@code content}：
 * <pre>
 *   开启思考：SSE 帧约 315 个，首个可见 content 出现前已推数百个推理帧
 *   关闭思考：SSE 帧约  49 个，第 3 帧即为真实内容
 * </pre>
 * 若不关闭，会带来两个后果：
 * <ol>
 *   <li><b>首字延迟指标失真</b> —— NFR 要求首字延迟 ≤ 3s，而用户感知的首字是第一个可见字符，
 *       推理帧不计入"可见输出"，却实实在在占用了等待时间；</li>
 *   <li><b>前端长时间空白</b> —— 推理阶段 {@code content} 恒为空串，用户会认为服务卡死。</li>
 * </ol>
 * 健康科普问答属知识性任务，思考链带来的准确率收益有限，故默认关闭，
 * 保留 {@code health.ai.thinking-enabled=true} 供消融实验开启。
 *
 * <p><b>实现说明</b>：{@code enable_thinking} 是百炼的<b>非标准参数</b>，OpenAI 官方协议无此字段，
 * 通过 Spring AI 的 {@link OpenAiChatOptions.Builder#extraBody(Map)} 透传。
 */
/**
 * 与 {@link RagChatService} 互斥：开启 RAG（{@code health.ai.rag-enabled=true}）时
 * 由 RAG 实现接管，本类不装配。条件写成表达式是因为要同时判断两个配置项。
 */
@Slf4j
@Service
@ConditionalOnExpression("'${health.ai.provider:dashscope}'=='dashscope' && '${health.ai.rag-enabled:false}'!='true'")
public class DashScopeChatService implements ChatService {

    private static final String SYSTEM_PROMPT = """
            你是一个面向大学生群体的健康科普助手。回答要求：
            1. 用通俗易懂的中文解释，避免不必要的专业术语；
            2. 内容须符合主流医学共识，不夸大、不臆断；
            3. 涉及诊断、用药或紧急情况时，明确建议就医并咨询专业医生；
            4. 回答控制在 500 字以内，先给结论再展开。
            """;

    private static final double TEMPERATURE = 0.3;

    private final OpenAiChatModel chatModel;
    private final boolean thinkingEnabled;
    /** 思考参数协议适配：百炼 {@code enable_thinking} / DeepSeek {@code thinking} */
    private final ReasoningParams reasoningParams;

    public DashScopeChatService(OpenAiChatModel chatModel,
                                @Value("${health.ai.thinking-enabled:false}") boolean thinkingEnabled,
                                ReasoningParams reasoningParams) {
        this.chatModel = chatModel;
        this.thinkingEnabled = thinkingEnabled;
        this.reasoningParams = reasoningParams;
        log.info("[DashScopeChatService] 已启用，model={}, thinkingEnabled={}",
                chatModel.getDefaultOptions().getModel(), thinkingEnabled);
    }

    @Override
    public String chat(ChatRequest request) {
        return chatModel.call(buildPrompt(request)).getResult().getOutput().getText();
    }

    @Override
    public Flux<ChatChunk> chatStream(ChatRequest request) {
        // 1) reference 先推：纯对话阶段为空列表，前端逻辑与 RAG 阶段完全一致
        Flux<ChatChunk> reference = Flux.just(ChatChunk.Reference.empty());

        // 2) delta 逐片推送；不产出 Done —— 由 Controller 组装（依赖落库后的 messageId 与耗时）
        Flux<ChatChunk> deltas = chatModel.stream(buildPrompt(request))
                // 流式 chunk 中可能出现空文本（usage 帧；开启思考时推理帧的 content 恒为空）
                .mapNotNull(resp -> {
                    String text = resp.getResult() == null
                            ? null : resp.getResult().getOutput().getText();
                    if (text == null || text.isEmpty()) {
                        return null;
                    }
                    ChatChunk chunk = new ChatChunk.Delta(text);
                    return chunk;
                });

        return Flux.concat(reference, deltas);
    }

    private Prompt buildPrompt(ChatRequest request) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(SYSTEM_PROMPT));
        if (request.history() != null) {
            for (ChatTurn turn : request.history()) {
                if (ChatTurn.ROLE_USER.equals(turn.role())) {
                    messages.add(new UserMessage(turn.content()));
                } else if (ChatTurn.ROLE_ASSISTANT.equals(turn.role())) {
                    messages.add(new AssistantMessage(turn.content()));
                }
            }
        }
        messages.add(new UserMessage(request.question()));

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .temperature(TEMPERATURE)
                // 百炼私有参数：显式关闭思考链，避免推理帧污染首字延迟与前端渲染
                .extraBody(reasoningParams.extraBody(thinkingEnabled))
                .build();

        return new Prompt(messages, options);
    }
}
