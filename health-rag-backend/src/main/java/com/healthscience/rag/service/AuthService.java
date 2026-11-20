package com.healthscience.rag.service;

import com.healthscience.rag.model.dto.AuthDTO;

/**
 * 认证服务。
 */
public interface AuthService {

    /**
     * 注册。
     *
     * @throws com.healthscience.rag.common.exception.BizException 用户名已存在（{@code 1001}）
     */
    void register(AuthDTO.RegisterRequest request);

    /**
     * 登录。
     *
     * @throws com.healthscience.rag.common.exception.BizException 用户名或密码错误（{@code 1002}）、
     *                                                        账号已禁用（{@code 1003}）
     */
    AuthDTO.LoginResponse login(AuthDTO.LoginRequest request);

    /**
     * 登出：将当前用户的 token 失效时间线推进到此刻（{@code sys_user.token_invalid_before}），
     * 该用户此前签发的全部 token 立即失效。
     *
     * <p>实现演进：V1 为无状态空实现；V2 起用失效时间线替代 Redis 黑名单（M9/B-4），
     * 该机制同时覆盖改密踢下线与管理端禁用踢下线（04-裁决 S5）。
     */
    void logout(Long userId);

    /** 当前登录用户档案 */
    AuthDTO.UserProfile me(Long userId);

    /** §三 #45 自助注销（B-26，委托 AccountService） */
    void deactivateSelf(Long userId, AuthDTO.DeactivateRequest request);

    /**
     * 修改个人资料（接口文档 §3.1）。仅覆盖请求中非 null 的字段，其余保持原值。
     */
    void updateProfile(Long userId, AuthDTO.UpdateProfileRequest request);

    /**
     * 修改密码（接口文档 §3.2）。
     *
     * @throws com.healthscience.rag.common.exception.BizException 原密码错误（{@code 1004}）
     */
    void updatePassword(Long userId, AuthDTO.UpdatePasswordRequest request);
}
