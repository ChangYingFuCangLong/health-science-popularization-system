package com.healthscience.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 会话健康事实（M16 v4.0 二次完善，08 §2.5）：专属会话中用户口头提到的身体指标，
 * 由 {@code HealthFactExtractor} 规则抽取落库（零 LLM 成本）。
 *
 * <p>随会话逻辑删除而失效（查询恒 JOIN 会话未删除状态）；⛔ 管理端不可见
 * （与画像同一隐私口径，不进任何日志/trace）。
 */
@Data
@TableName("chat_health_fact")
public class ChatHealthFact {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    private Long userId;

    /** 指标目录 key：weight / systolic / diastolic / glucose / heart_rate / sleep_hours / steps … */
    private String metricKey;

    private BigDecimal value;

    private String unit;

    /** 来源用户消息（可溯源到轮次） */
    private Long messageId;

    private LocalDateTime createTime;
}
