package com.healthscience.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 答案反馈，对应 {@code message_feedback} 表（接口文档 §4.7）。
 */
@Data
@TableName("message_feedback")
public class MessageFeedback {

    /** 有用 */
    public static final String TYPE_USEFUL = "useful";
    /** 无用 */
    public static final String TYPE_USELESS = "useless";
    /** 内容有误 */
    public static final String TYPE_WRONG = "wrong";

    /** 待处理 */
    public static final int STATUS_PENDING = 0;
    /** 已处理 */
    public static final int STATUS_HANDLED = 1;

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long messageId;
    private Long userId;
    private String type;
    private String comment;
    private Integer status;
    private Long handleBy;
    private LocalDateTime handleTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    @TableLogic
    private Boolean deleted;
}
