package com.healthscience.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 问答会话实体，对应 {@code chat_session} 表。
 *
 * <p>不设 {@code message_count} 冗余字段 —— 流中断会导致计数失真，
 * 列表页用子查询统计（见 ChatSessionMapper.selectSessionList）。
 */
@Data
@TableName("chat_session")
public class ChatSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 会话标题，取首条提问前 20 字，缺省「新对话」；专属对话固定「我的专属对话」 */
    private String title;

    /** 会话类型（M16 v4.0）：normal 通用解惑 / health 个人解惑（可多条，08 §一）；file 由 B-31 扩展 */
    private String kind;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Boolean deleted;

    public static final String KIND_NORMAL = "normal";
    public static final String KIND_HEALTH = "health";
}
