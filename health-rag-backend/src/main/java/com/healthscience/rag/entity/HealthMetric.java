package com.healthscience.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 健康指标，对应 {@code health_metric} 表（04-裁决 §三，前端「健康中心」P0）。
 *
 * <p><b>全库唯一无 {@code deleted} 列的表</b>：记录为用户个人自助数据，
 * 删除即物理消失（符合健康数据隐私预期，02-接口文档 §十）。
 * 这是特性不是遗漏，论文「数据库设计」章需注明。
 *
 * <p>{@code value} 语义随 {@code type} 变化：体重 kg / 收缩压 / 睡眠小时 / 步数；
 * {@code value2} 仅 bp（舒张压）使用，其余类型必须为 NULL（服务层校验）。
 */
@Data
@TableName("health_metric")
public class HealthMetric {

    public static final String TYPE_WEIGHT = "weight";
    public static final String TYPE_BP = "bp";
    public static final String TYPE_SLEEP = "sleep";
    public static final String TYPE_STEPS = "steps";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** weight / bp / sleep / steps */
    private String type;

    private BigDecimal value;

    /** 仅 bp：舒张压 */
    private BigDecimal value2;

    private LocalDateTime recordedAt;

    private String note;

    /** manual 手工录入 / report 报告抽取（B-30） */
    private String source;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
