package com.healthscience.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户健康画像，对应 {@code user_portrait} 表（M16，契约 08）。
 *
 * <p><b>单用户单条</b>（库级唯一索引 {@code uk_portrait_user}），应用层 UPSERT。
 * <b>物理删除、无 deleted 列</b>——与 health_metric 同一裁决口径（用户自助健康数据，
 * 删除即消失；论文"数据最小化"可引此例）。
 *
 * <p><b>隐私红线（08 §2.2）</b>：本表内容只允许出现在两个地方——本表自身、
 * 请求期间组装的系统提示（内存）。<b>绝不写入 chat_message 的任何列</b>
 * （query_text / trace_json / references_json 管理端可见，用户病史不得变成管理员可读）。
 *
 * <p>conditions / diet / goals 存英文枚举码的 JSONB 数组（稳定、可统计），
 * 中文标签由字段字典接口下发，前端不得硬编码。
 */
@Data
@TableName("user_portrait")
public class UserPortrait {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** male / female / other */
    private String gender;

    private Integer birthYear;

    private Integer heightCm;

    private BigDecimal weightKg;

    /** 慢病标签数组 JSON：["hypertension",...] */
    private String conditions;

    private String allergies;

    private String medications;

    /** never / rare / weekly1_2 / weekly3_5 / daily */
    private String exercise;

    private BigDecimal sleepHours;

    /** never / quit / light / heavy */
    private String smoking;

    /** never / rarely / social / frequent */
    private String drinking;

    /** 饮食偏好标签数组 JSON：["low_salt",...] */
    private String diet;

    /** 目标标签数组 JSON：["lose_weight",...] */
    private String goals;

    private String note;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
