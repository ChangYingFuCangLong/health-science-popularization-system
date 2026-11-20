package com.healthscience.rag.ai.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 报告结构化抽取的 LLM 调用（B-30；C1/C2：Spring AI 类型只出现在 ai 包内）。
 *
 * <p>纯"调模型拿 JSON"——目录映射 prompt 由业务层生成传入；
 * 失败返回 null 由业务层降级（草稿退化为文本摘要项）。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "health.ai.provider", havingValue = "dashscope")
public class ReportLlmExtractor {

    private final OpenAiChatModel chatModel;
    private final ReasoningParams reasoningParams;

    public ReportLlmExtractor(OpenAiChatModel chatModel, ReasoningParams reasoningParams) {
        this.chatModel = chatModel;
        this.reasoningParams = reasoningParams;
    }

    /**
     * @param reportText 报告文本（已截断）
     * @param prompt     业务侧组装的 System Prompt（含目录 key 映射）
     * @return 模型原始输出（JSON 文本）；失败 null
     */
    public String extract(String reportText, String prompt) {
        try {
            List<Message> messages = List.of(new SystemMessage(prompt), new UserMessage(reportText));
            return chatModel.call(new Prompt(messages, OpenAiChatOptions.builder()
                            .temperature(0.0)
                            .extraBody(reasoningParams.extraBody(false))
                            .build()))
                    .getResult().getOutput().getText();
        } catch (Exception e) {
            log.warn("[ReportLLM] 抽取调用失败: {}", e.getMessage());
            return null;
        }
    }
}
