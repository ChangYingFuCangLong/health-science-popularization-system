package com.healthscience.rag.model.dto;

import com.healthscience.rag.ai.model.ChunkRef;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话与消息的传输对象，对应接口文档 §4.1–§4.5。
 *
 * <p>引用 {@link ChunkRef} 是自定义类型（非框架类型），不违反约束 C1/C2。
 */
public final class ChatDTO {

    private ChatDTO() {
    }

    /** §4.1 新建会话入参 */
    public record CreateSessionRequest(String title) {
    }

    /** §4.3 重命名入参 */
    public record RenameRequest(String title) {
    }

    /** §4.6 重新生成的准备结果 */
    public record RetryContext(
            /** 原问题文本 */
            String question,
            /** 原问题消息 ID：组装上下文时要排除它，否则它会与 finalQuestion 重复 */
            Long questionMessageId
    ) {
    }

    /** §4.2 会话列表项（v4.0：AI 解惑页恒 kind=normal，健康对话列表走 #58 专用接口，DTO 无需 kind） */
    public record SessionVO(
            Long id,
            String title,
            Integer messageCount,
            LocalDateTime createTime,
            LocalDateTime updateTime
    ) {
    }

    /** §4.9 追问建议响应 */
    public record SuggestionsVO(List<String> questions) {
    }

    /** §4.5 消息项 */
    public record MessageVO(
            Long id,
            String role,
            String content,
            List<ChunkRef> references,
            String retrieveMode,
            Integer latencyMs,
            Integer firstTokenMs,
            Integer finishStatus,
            Integer isRejected,
            LocalDateTime createTime
    ) {
    }
}
