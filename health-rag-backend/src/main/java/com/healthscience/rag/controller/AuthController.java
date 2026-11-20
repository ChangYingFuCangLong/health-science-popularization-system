package com.healthscience.rag.controller;

import com.healthscience.rag.common.result.R;
import com.healthscience.rag.common.security.UserContext;
import com.healthscience.rag.model.dto.AuthDTO;
import com.healthscience.rag.service.AuthService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（接口文档 §2）。
 *
 * <p>{@code register} / {@code login} 免认证，其余需携带
 * {@code Authorization: Bearer <token>}。
 */
@Tag(name = "认证 /api/auth")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** 注册（§2.1） */
    @PostMapping("/register")
    public R<Void> register(@Valid @RequestBody AuthDTO.RegisterRequest request) {
        authService.register(request);
        return R.ok();
    }

    /** 登录（§2.2） */
    @PostMapping("/login")
    public R<AuthDTO.LoginResponse> login(@Valid @RequestBody AuthDTO.LoginRequest request) {
        return R.ok(authService.login(request));
    }

    /** 登出（§2.3）：服务端把该用户 token 失效时间线推进到此刻，旧 token 立即 401 */
    @PostMapping("/logout")
    public R<Void> logout() {
        authService.logout(UserContext.requireId());
        return R.ok();
    }

    /** §三 #45 自助注销（B-26）：密码校验 + 个人资产全量清理 + 踢下线 */
    @PostMapping("/account/deactivate")
    public R<Void> deactivate(@jakarta.validation.Valid @RequestBody AuthDTO.DeactivateRequest request) {
        authService.deactivateSelf(UserContext.requireId(), request);
        return R.ok();
    }

    /** 当前用户信息（§2.4） */
    @GetMapping("/me")
    public R<AuthDTO.UserProfile> me() {
        return R.ok(authService.me(UserContext.requireId()));
    }
}
