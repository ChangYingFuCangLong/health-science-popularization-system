package com.healthscience.rag.controller;

import com.healthscience.rag.common.result.R;
import com.healthscience.rag.common.security.UserContext;
import com.healthscience.rag.model.dto.AuthDTO;
import com.healthscience.rag.service.AuthService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户接口（接口文档 §3）。
 *
 * <p>需携带 {@code Authorization: Bearer <token>}。资料与密码归属本人，
 * 操作目标一律取当前登录用户，不接受路径或参数指定他人 ID。
 */
@Tag(name = "用户 /api/user")
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final AuthService authService;

    /** §3.1 修改个人资料 */
    @PutMapping("/profile")
    public R<Void> updateProfile(@Valid @RequestBody AuthDTO.UpdateProfileRequest request) {
        authService.updateProfile(UserContext.requireId(), request);
        return R.ok();
    }

    /** §3.2 修改密码 */
    @PutMapping("/password")
    public R<Void> updatePassword(@Valid @RequestBody AuthDTO.UpdatePasswordRequest request) {
        authService.updatePassword(UserContext.requireId(), request);
        return R.ok();
    }
}
