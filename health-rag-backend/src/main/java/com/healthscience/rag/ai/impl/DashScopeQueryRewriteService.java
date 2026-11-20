package com.healthscience.rag.ai.impl;

import com.healthscience.rag.ai.QueryRewriteService;
import com.healthscience.rag.ai.model.ChatTurn;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 查询改写实现（DashScope qwen，flash 级模型，单次调用约 200ms）。
 *
 * <p><b>铁律：改写绝不能影响主链路</b> —— 开关关闭直接原样返回；
 * 任何异常（超时/限流/输出为空或过长）都降级为原问题。
 * 与 {@code LangSmithTracer} 同一纪律：增强组件的失败不是故障。
 *
 * <p>provider 非 dashscope（ollama/mock）时不装配本类，
 * {@code RagChatService} 经 ObjectProvider 探测不到即跳过改写。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "health.ai.provider", havingValue = "dashscope")
public class DashScopeQueryRewriteService implements QueryRewriteService {

    private static final String PROMPT = """
            你是健康问答系统的检索查询改写器。把用户的最新提问改写成一条独立、自包含的检索查询：
            1. 依据对话历史，把提问中的代词（如"它/这个/那"）替换为具体名词；
            2. 补全省略的上下文，使这条查询脱离对话历史也能被准确理解；
            3. 保持中文，不要回答问题，不要解释，不要加引号；
            4. 只输出一行改写后的查询；若原提问已经自包含，原样输出。
            """;

    /** 改写输出异常长视为模型跑偏（正常查询 ≤ 60 字），降级用 */
    private static final int REWRITE_MAX_LEN = 200;

    /** 最多携带的历史轮数（user+assistant 各算一轮） */
    private static final int HISTORY_TURNS = 3;

    private final OpenAiChatModel chatModel;
    private final boolean enabled;
    private final ReasoningParams reasoningParams;

    public DashScopeQueryRewriteService(
            OpenAiChatModel chatModel,
            @Value("${health.rag.rewrite-enabled:true}") boolean enabled,
            ReasoningParams reasoningParams) {
        this.chatModel = chatModel;
        this.enabled = enabled;
        this.reasoningParams = reasoningParams;
        log.info("[QueryRewrite] 已装配，enabled={}", enabled);
    }

    @Override
    public String rewrite(List<ChatTurn> history, String question) {
        if (!enabled || question == null || question.isBlank()) {
            return question;
        }
        try {
            String ctx = renderHistory(history);
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(PROMPT));
            messages.add(new UserMessage(ctx.isEmpty()
                    ? "用户最新提问：" + question
                    : "对话历史：\n" + ctx + "\n\n用户最新提问：" + question));

            String out = chatModel.call(new Prompt(messages, options()))
                    .getResult().getOutput().getText();
            String rewritten = out == null ? "" : out.trim();
            // 输出异常（空/跑偏过长）一律降级为原问题
            if (rewritten.isEmpty() || rewritten.length() > REWRITE_MAX_LEN
                    || rewritten.contains("\n")) {
                log.info("[QueryRewrite] 输出异常（len={}），降级为原问题", rewritten.length());
                return question;
            }
            return rewritten;
        } catch (Exception e) {
            log.warn("[QueryRewrite] 改写失败，降级为原问题: {}", e.getMessage());
            return question;
        }
    }

    /** 历史转纯文本（正序，user/assistant 前缀），超预算直接截掉更早轮次 */
    private String renderHistory(List<ChatTurn> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        List<ChatTurn> recent = history.subList(Math.max(0, history.size() - HISTORY_TURNS * 2), history.size());
        StringBuilder sb = new StringBuilder();
        for (ChatTurn turn : recent) {
            sb.append("user".equals(turn.role()) ? "用户：" : "助手：")
                    .append(turn.content() == null ? "" : turn.content())
                    .append('\n');
        }
        return sb.toString().strip();
    }

    private OpenAiChatOptions options() {
        return OpenAiChatOptions.builder()
                .temperature(0.2)
                .extraBody(reasoningParams.extraBody(false))
                .build();
    }
}
