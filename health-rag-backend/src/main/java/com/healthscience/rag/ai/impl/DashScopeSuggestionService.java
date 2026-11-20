package com.healthscience.rag.ai.impl;

import com.healthscience.rag.ai.SuggestionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 追问建议实现（DashScope qwen，flash 级 + temperature 0.5，契约 02 §4.9）。
 *
 * <p><b>降级纪律</b>（与 QueryRewriteService 一致）：任何失败返回空列表，
 * 业务层不缓存空结果 —— 下次点击重试，绝不让演示卡死。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "health.ai.provider", havingValue = "dashscope")
public class DashScopeSuggestionService implements SuggestionService {

    private static final String PROMPT = """
            你是健康科普问答系统的追问建议器。根据助手回答（及其引用来源标题），\
            生成用户最可能继续追问的 3~4 个问题：
            1. 问题必须是用户视角、面向本条回答内容的自然追问（怎么做到/有哪些注意事项/什么情况要就医等）；
            2. 每行只输出一个问题，不要编号、不要引号、不要解释；
            3. 每个问题不超过 40 字。
            """;

    private static final int MAX_SUGGESTIONS = 4;
    private static final int MAX_LEN = 60;

    private final OpenAiChatModel chatModel;
    private final ReasoningParams reasoningParams;

    public DashScopeSuggestionService(OpenAiChatModel chatModel, ReasoningParams reasoningParams) {
        this.chatModel = chatModel;
        this.reasoningParams = reasoningParams;
        log.info("[Suggestion] 已装配");
    }

    @Override
    public List<String> suggest(String answer, List<String> referenceTitles) {
        try {
            StringBuilder user = new StringBuilder("助手回答：\n")
                    .append(answer == null ? "" : answer)
                    .append('\n');
            if (referenceTitles != null && !referenceTitles.isEmpty()) {
                user.append("引用来源：").append(String.join("、", referenceTitles)).append('\n');
            }

            List<Message> messages = List.of(
                    new SystemMessage(PROMPT),
                    new UserMessage(user.toString()));
            String out = chatModel.call(new Prompt(messages, options()))
                    .getResult().getOutput().getText();
            if (out == null || out.isBlank()) {
                return List.of();
            }
            return Arrays.stream(out.split("\n"))
                    .map(line -> line.replaceAll("^\\s*[0-9０-９]+[.、．)）]\\s*", "").trim())
                    .filter(line -> !line.isEmpty() && line.length() <= MAX_LEN)
                    .distinct()
                    .limit(MAX_SUGGESTIONS)
                    .toList();
        } catch (Exception e) {
            log.warn("[Suggestion] 生成失败，返回空列表: {}", e.getMessage());
            return List.of();
        }
    }

    private OpenAiChatOptions options() {
        return OpenAiChatOptions.builder()
                .temperature(0.5)
                .extraBody(reasoningParams.extraBody(false))
                .build();
    }
}
