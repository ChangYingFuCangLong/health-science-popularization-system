package com.healthscience.rag.model.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 个人知识库传输对象（M15，契约 07 §5）。
 */
public final class UserKbDTO {

    private UserKbDTO() {
    }

    /** #44 概览与配额 */
    public record StatsVO(
            Integer documentCount,
            Integer chunkCount,
            Long totalSize,
            Integer readyCount,
            Integer processingCount,
            Integer failedCount,
            Integer pendingChunks,
            QuotaVO quota
    ) {
    }

    /** #44 配额（max* 供前端画进度条，前端不硬编码阈值） */
    public record QuotaVO(
            Integer maxDocuments,
            Long maxTotalSize,
            Integer maxTotalChunks,
            Integer usedDocuments,
            Long usedTotalSize,
            Integer usedTotalChunks
    ) {
    }

    /** #45 文档列表项 */
    public record DocumentVO(
            Long id,
            String title,
            String fileType,
            Long fileSize,
            Integer status,
            String failReason,
            Integer chunkCount,
            LocalDateTime createTime
    ) {
    }

    /** #47 处理进度 */
    public record ProgressVO(
            Long documentId,
            Integer status,
            Integer done,
            Integer total,
            String failReason
    ) {
    }

    /** #48 分块预览项（复用 ChunkVO 语义，去掉管理端专用字段） */
    public record ChunkPreviewVO(
            Long id,
            Integer chunkIndex,
            String section,
            String content,
            Integer charCount
    ) {
    }

    /** #51/#52 偏好设置（v4.0：includeInChat 唯一开关；enabled 为只读派生） */
    public record SettingsVO(
            Boolean enabled,
            Boolean includeInChat
    ) {
    }

    /** #52 更新请求 */
    public record SettingsUpdateRequest(
            Boolean includeInChat
    ) {
    }

    /** #45 分页体包裹（与 02 §1.2 一致由 PageResult 承担，此处仅文档数组语义别名） */
    public record DocumentPageVO(
            Long total,
            Long pages,
            Long current,
            Long size,
            List<DocumentVO> records
    ) {
    }
}
