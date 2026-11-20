package com.healthscience.rag.model.dto;

import com.healthscience.rag.entity.SysUser;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.format.DateTimeFormatter;

/**
 * 认证模块的数据传输对象，字段与接口文档 §2 严格对应。
 *
 * <p>时间统一输出 {@code yyyy-MM-dd HH:mm:ss}（接口文档 §1 通用约定）。
 */
public final class AuthDTO {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private AuthDTO() {
    }

    /** 注册请求（§2.1） */
    public record RegisterRequest(
            @NotBlank(message = "用户名不能为空")
            @Pattern(regexp = "^[A-Za-z0-9_]{4,20}$",
                    message = "用户名需为 4~20 位字母、数字或下划线")
            String username,

            @NotBlank(message = "密码不能为空")
            @Size(min = 6, max = 32, message = "密码长度需为 6~32 位")
            String password,

            @Size(max = 20, message = "昵称不超过 20 字")
            String nickname
    ) {
    }

    /** 登录请求（§2.2） */
    public record LoginRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "密码不能为空") String password
    ) {
    }

    /** 登录响应（§2.2） */
    public record LoginResponse(String token, long expiresIn, UserInfo userInfo) {
    }

    /** 简要用户信息，内嵌于登录响应 */
    public record UserInfo(Long id, String username, String nickname, String role, String avatar) {

        public static UserInfo from(SysUser user) {
            return new UserInfo(user.getId(), user.getUsername(),
                    user.getNickname(), user.getRole(), user.getAvatar());
        }
    }

    /** 用户档案（§2.4 /auth/me） */
    public record UserProfile(Long id, String username, String nickname, String email,
                              String avatar, String role, String createTime) {

        public static UserProfile from(SysUser user) {
            return new UserProfile(user.getId(), user.getUsername(), user.getNickname(),
                    user.getEmail(), user.getAvatar(), user.getRole(),
                    user.getCreateTime() == null ? null : user.getCreateTime().format(TIME_FORMAT));
        }
    }

    /** 修改个人资料请求（§3.1）。字段可选：null 表示保持原值不变 */
    public record UpdateProfileRequest(
            @Size(max = 20, message = "昵称不超过 20 字")
            String nickname,

            @Size(max = 100, message = "邮箱不超过 100 字")
            String email,

            @Size(max = 255, message = "头像地址不超过 255 字")
            String avatar
    ) {
    }

    /** 注销请求（§三 #45）：密码校验通过才执行清理 */
    public record DeactivateRequest(
            @NotBlank(message = "密码不能为空")
            String password
    ) {
    }

    /** 修改密码请求（§3.2） */
    public record UpdatePasswordRequest(
            @NotBlank(message = "原密码不能为空")
            String oldPassword,

            @NotBlank(message = "新密码不能为空")
            @Size(min = 6, max = 32, message = "新密码长度需为 6~32 位")
            String newPassword
    ) {
    }
}
