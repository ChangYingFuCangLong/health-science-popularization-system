package com.healthscience.rag.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户个人知识库偏好，对应 {@code user_kb_setting} 表（M15，契约 07 #51/#52）。
 *
 * <p>按用户一行（user_id 主键）；<b>无行 = 默认偏好</b>（参与问答、默认 all），
 * 服务端按缺省处理，无需为每个用户插行。
 */
@Data
@TableName("user_kb_setting")
public class UserKbSetting {

    @TableId
    private Long userId;

    /** 个人资料是否参与问答检索（false 时服务端强制忽略 kbScope 的 personal 成分） */
    private Boolean includeInChat;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
