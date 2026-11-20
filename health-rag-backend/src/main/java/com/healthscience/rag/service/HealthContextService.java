package com.healthscience.rag.service;

import java.util.Map;

/**
 * 健康上下文组装（M16 v4.0 二次完善，08 §2.3/2.5）：三层/四层合并注入 + 会话事实落库。
 *
 * <p><b>注入优先级</b>（高→低）：本轮用户陈述 &gt; ③ 会话事实 &gt; ② 档案最新值 &gt; ① 画像声明，
 * 合并为一段 ≤800 字的"当前健康状况"注入健康对话 System Prompt。
 * <b>隐私</b>：产物只在内存，绝不写入 chat_message 任何列。
 */
public interface HealthContextService {

    /** 层① 画像注入文本（≤600 字，复用画像服务）；空 → null */
    String portraitSection(Long userId);

    /**
     * 组装健康对话的合并上下文（≤800 字）。
     *
     * @return 全部层为空时返回 null（调用方据此决定 healthUsed）
     */
    String buildHealthContext(Long userId, Long sessionId);

    /**
     * 规则抽取本轮用户陈述中的健康指标并落 chat_health_fact（仅健康会话调用）。
     *
     * @return 抽取条数（0 = 无命中）
     */
    int extractAndSaveFacts(Long sessionId, Long userId, Long messageId, String text);

    /**
     * 从模型输出中剥离【HEALTH_REMINDER】/【HEALTH_SUGGESTION】标记。
     *
     * @return 剥离后的正文；reminder/suggestion 为解析结果（无则 null）
     */
    StripResult stripMarkers(String content);

    /** 剥离结果：healthReminder 结构 {field, fieldLabel, recordedValue, statedValue} */
    record StripResult(String content, Map<String, Object> healthReminder, String healthSuggestion) {
    }
}
