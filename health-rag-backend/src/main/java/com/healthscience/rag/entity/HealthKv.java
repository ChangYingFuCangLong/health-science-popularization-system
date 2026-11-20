package com.healthscience.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 健康备忘 KV（M16 v4.0，B-30，契约 11 号）：自由文本健康信息 + 报告抽取的文本项。
 *
 * <p>一人一 key 覆盖（唯一索引）；≤50 条/人由应用层校验（超限 2006）。
 * 第四层注入来源（≤300 字摘要）。
 */
@Data
@TableName("health_kv")
public class HealthKv {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 备忘名（自由文本 ≤50 字，可重复——"用药"可有两条） */
    private String itemKey;

    /** 备忘内容（≤500 字） */
    private String itemValue;

    /** manual 手工 / report 报告抽取 */
    private String source;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
