package com.healthscience.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 指标目录（M16 v4.0，B-29，契约 10 号）：标准指标（owner NULL）+ 用户自定义（≤10/人）。
 *
 * <p>健康指标/画像/报告抽取的取值校验一律查本目录（单一真相）；
 * {@code portrait_sync} 标准指标参与画像同步；deleted 逻辑删后记录只读保留。
 */
@Data
@TableName("metric_def")
public class MetricDef {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 稳定英文码（health_metric.type / health_kv.key / 抽取 key 均引用它） */
    private String metricKey;

    private String label;

    private String unit;

    private BigDecimal valueMin;

    private BigDecimal valueMax;

    /** 血压类指标需要第二个数值（舒张压） */
    private Boolean hasValue2;

    private String value2Label;

    /** 体征 / 血压心肺 / 代谢 / 睡眠运动 …（标准指标的业务分类） */
    private String category;

    /** 自定义指标 owner；标准指标恒 NULL */
    private Long ownerUserId;

    @TableLogic
    private Boolean deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
