package com.healthscience.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体，对应 {@code sys_user} 表。
 *
 * <p>{@code create_time} / {@code update_time} 由数据库默认值维护，未使用填充器 ——
 * 少一处配置，少一处出错点。
 */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** BCrypt 加密后的密文。任何情况下都不要序列化到响应中 */
    private String password;

    private String nickname;

    private String email;

    private String avatar;

    /** 角色：USER / ADMIN */
    private String role;

    /** 状态：0 禁用，1 正常 */
    private Integer status;

    /**
     * token 失效时间线：签发时间早于该时刻的 token 一律拒绝。
     * 登出 / 改密 / 禁用共用此机制（02-接口文档 §2.3/§3.2/§6.14），不引入 Redis 黑名单。
     */
    private LocalDateTime tokenInvalidBefore;

    private LocalDateTime lastLoginAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Boolean deleted;
}
