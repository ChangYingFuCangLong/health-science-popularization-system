package com.healthscience.rag.ai.model;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 生成结束时的统计信息，由 Controller 组装（见 {@link ChatChunk} 职责边界）。
 *
 * @param messageId     落库后的助手消息 ID
 * @param sessionId     所属会话 ID
 * @param latencyMs     响应总耗时（毫秒）
 * @param firstTokenMs  首字延迟（毫秒）—— 从请求发起到推送首个 {@code delta} 的后端打点（ADR-017）
 * @param finishStatus  完成状态：0 未完成（流中断、内容已保留），1 已完成
 * @param content       完整回答全文。前端合帧节流下可能出现渲染堆积，
 *                      用全文校正一次即可兜住，无需前端自行拼接维护
 * @param healthUsed    本次回答是否真的注入了健康上下文（M16 v4.0，08 §5.4③）。
 * @param answerCount   对照解答条数（B-27：双答案时为 2，单答案缺省 null）。
 *                      只在专属对话且画像非空时为 true——<b>服务端说了算</b>，
 *                      前端不得按会话类型想当然显示"已参考"
 */
public record DoneInfo(
        Long messageId,
        Long sessionId,
        int latencyMs,
        int firstTokenMs,
        int finishStatus,
        String content,
        Boolean healthUsed,
        Map<String, Object> healthReminder,
        String healthSuggestion,
        Integer answerCount
) {

    public static final int FINISH_INCOMPLETE = 0;
    public static final int FINISH_COMPLETED = 1;
}
