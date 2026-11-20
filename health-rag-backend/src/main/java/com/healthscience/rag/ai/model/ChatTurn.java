package com.healthscience.rag.ai.model;

/**
 * 一轮对话消息。
 *
 * <p>框架无关：不暴露 Spring AI 的 {@code Message} 类型（见架构约束 C1/C2）。
 *
 * @param role    角色：{@code user} / {@code assistant}
 * @param content 内容
 */
public record ChatTurn(String role, String content) {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    public static ChatTurn user(String content) {
        return new ChatTurn(ROLE_USER, content);
    }

    public static ChatTurn assistant(String content) {
        return new ChatTurn(ROLE_ASSISTANT, content);
    }
}
