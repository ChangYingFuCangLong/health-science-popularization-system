package com.healthscience.rag.ai.model;

import java.util.List;

/**
 * 对话请求参数。
 *
 * <p>刻意收敛为一个对象而非散列参数：后续新增字段（如 temperature、topK 覆盖）
 * 不再改动接口签名（见 {@code 系统设计.md} §4.2）。
 *
 * @param history            历史对话，已按 token 预算裁剪（约 3000 token）
 * @param question           本轮提问
 * @param retryFromMessageId 可选｜「重新生成」时指定被替换的助手消息 ID
 * @param retrieveMode       可选｜检索策略，取值见 {@link RetrieveMode}；纯对话阶段为 {@code none}
 * @param kbScope            可选｜检索范围 all/global/personal（M15，07 §3.3）；null 视为 global
 * @param ownerUserId        可选｜当前用户（personal/all 检索语义需要）；无登录语境为 null
 * @param healthContext      可选｜健康上下文注入文本（M16 v4.0，08 §2：只影响生成不参与检索；
 *                           由服务端按会话推导，前端不可传；null = 不注入）
 * @param sessionKind        可选｜会话类型 normal/health/file（双答案判定用）
 */
public record ChatRequest(
        List<ChatTurn> history,
        String question,
        Long retryFromMessageId,
        String retrieveMode,
        String kbScope,
        Long ownerUserId,
        String healthContext,
        String sessionKind,
        Long sessionId
) {

    /** 纯对话（不检索）的构造快捷方式 */
    public static ChatRequest of(List<ChatTurn> history, String question) {
        return new ChatRequest(history, question, null, RetrieveMode.NONE, null, null, null, null, null);
    }

    /** 是否要求执行「重新生成」 */
    public boolean isRetry() {
        return retryFromMessageId != null;
    }

    /** 归一化后的检索策略，空值视为 {@code none} */
    public String effectiveRetrieveMode() {
        return (retrieveMode == null || retrieveMode.isBlank()) ? RetrieveMode.NONE : retrieveMode;
    }
}
