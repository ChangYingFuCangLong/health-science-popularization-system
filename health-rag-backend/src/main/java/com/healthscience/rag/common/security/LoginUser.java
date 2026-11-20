package com.healthscience.rag.common.security;

/**
 * 当前登录用户（作为 {@code SecurityContext} 的 principal）。
 *
 * <p>只保留会话期间必需的最小字段，避免把完整实体放进安全上下文。
 *
 * @param id       用户 ID
 * @param username 用户名
 * @param role     角色：{@code USER} / {@code ADMIN}
 */
public record LoginUser(Long id, String username, String role) {

    public static final String ROLE_USER = "USER";
    public static final String ROLE_ADMIN = "ADMIN";

    public boolean isAdmin() {
        return ROLE_ADMIN.equals(role);
    }
}
