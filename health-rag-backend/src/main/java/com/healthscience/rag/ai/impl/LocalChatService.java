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
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话能力的本地降级实现（Ollama）。
 *
 * <p><b>定位</b>（失败模式表）：云端主实现（当前为阿里百炼）超时/限流/不可用时走本通道，
 * 回答质量下降但功能可用 —— 这是 AI 接口层（ADR-004）带来的直接收益。
 *
 * <p>模型名通过 {@code app.chat.local-model} 配置（默认 qwen3.5:7b，本地已有的对话模型）。
 *
 * <p><b>启用方式</b>：{@code health.ai.provider=ollama}。默认不装配，
 * 与 {@link DashScopeChatService}、{@link MockChatService} 三者互斥。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "health.ai.provider", havingValue = "ollama")
public class LocalChatService implements ChatService {

    private static final String SYSTEM_PROMPT = """
            你是一个健康科普助手，用通俗中文回答，涉及诊疗建议时提醒就医。
            """;

    private final OllamaChatModel chatModel;
    private final String localModel;

    public LocalChatService(OllamaChatModel chatModel,
                            @Value("${app.chat.local-model:qwen3.5:7b}") String localModel) {
        this.chatModel = chatModel;
        this.localModel = localModel;
    }

    @Override
    public String chat(ChatRequest request) {
        return chatModel.call(buildPrompt(request)).getResult().getOutput().getText();
    }

    @Override
    public Flux<ChatChunk> chatStream(ChatRequest request) {
        Flux<ChatChunk> reference = Flux.just(ChatChunk.Reference.empty());

        Flux<ChatChunk> deltas = chatModel.stream(buildPrompt(request))
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
        // Ollama 的 chat 模型与 embedding 不同，须在调用处显式指定
        return new Prompt(messages, OllamaChatOptions.builder().model(localModel).build());
    }
}
