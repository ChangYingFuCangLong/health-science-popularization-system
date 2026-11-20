package com.healthscience.rag.model.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 管理端传输对象，对应接口文档 §6 与 §7。
 */
public final class AdminDTO {

    private AdminDTO() {
    }

    /** §6.5 文档分块项 */
    public record ChunkVO(
            Long id,
            Integer chunkIndex,
            String section,
            String content,
            Integer charCount,
            Map<String, Object> metaJson
    ) {
    }

    /** §6.2/6.3 上传处理进度 */
    public record DocumentProgressVO(
            Long documentId,
            Integer status,
            Integer chunksDone,
            Integer chunksTotal,
            String failReason
    ) {
    }

    /** §6.7 向量索引元信息 */
    public record VectorMetaVO(
            String embeddingModel,
            Integer dimension,
            Integer chunkTotal,
            Integer status,
            LocalDateTime builtAt
    ) {
    }

    /** §6.8 数据看板 */
    public record OverviewVO(
            int totalUsers,
            int totalSessions,
            int totalMessages,
            int todayMessages,
            double rejectRate,
            int avgLatencyMs,
            int avgFirstTokenMs,
            int docCount,
            int chunkCount,
            List<TrendPoint> trend,
            List<HotQuestion> hotQuestions
    ) {
    }

    /** §6.8 近 7 日问答量 */
    public record TrendPoint(String date, int count) {
    }

    /** §6.8 热门问题 */
    public record HotQuestion(String question, int count) {
    }

    /** §6.9 问答日志项 */
    public record ChatLogVO(
            Long messageId,
            Long sessionId,
            Long userId,
            String username,
            String question,
            String answer,
            String queryText,
            String retrieveMode,
            Integer latencyMs,
            Integer isRejected,
            Integer finishStatus,
            LocalDateTime createTime
    ) {
    }

    /**
     * §6.11 用户列表项。
     *
     * <p><b>刻意不含 password 字段</b>：直接返回实体会把 BCrypt 哈希带给前端，
     * 属于不该发生的敏感信息泄露。
     */
    public record UserVO(
            Long id,
            String username,
            String nickname,
            String email,
            String role,
            Integer status,
            LocalDateTime lastLoginAt,
            LocalDateTime createTime
    ) {
    }

    /** §6.10 反馈列表项（带被反馈的提问，便于管理端定位） */
    public record FeedbackVO(
            Long id,
            Long messageId,
            Long userId,
            String username,
            String type,
            String comment,
            Integer status,
            String answerDigest,
            LocalDateTime createTime
    ) {
    }

    /** §7.1 评测问题项 */
    public record EvalItemVO(
            Long id,
            String question,
            String groundTruth,
            List<Long> refChunkIds,
            String category,
            Integer difficulty,
            LocalDateTime createTime
    ) {
    }

    /**
     * §7.4 实验结果项。
     *
     * <p>{@code retrieved} 来自 {@code retrieved_json} 的解析结果（[{chunkId,rank,score}]），
     * {@code scoreSource} 取值 AUTO（LLM 判分）/ MANUAL（人工复核）/ null（未评分）。
     */
    public record EvalResultVO(
            Long id,
            String batchNo,
            Long datasetId,
            String question,
            String strategy,
            Integer hitRank,
            Integer isHit,
            List<Map<String, Object>> retrieved,
            String answer,
            BigDecimal faithfulness,
            BigDecimal citationCorrect,
            String scoreSource,
            Integer latencyMs
    ) {
    }

    /** §7.5 实验指标汇总项 */
    public record EvalSummaryItemVO(
            String strategy,
            Double hitRate5,
            Double mrr,
            Double avgFaithfulness,
            Integer avgLatencyMs,
            Integer sampleCount
    ) {
    }

    /** §7.5 汇总响应 */
    public record EvalSummaryVO(String batchNo, List<EvalSummaryItemVO> items) {
    }
}
