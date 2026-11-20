package com.healthscience.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.healthscience.rag.common.exception.BizException;
import com.healthscience.rag.common.result.ErrorCode;
import com.healthscience.rag.common.security.JwtUtil;
import com.healthscience.rag.common.security.LoginUser;
import com.healthscience.rag.entity.SysUser;
import com.healthscience.rag.mapper.SysUserMapper;
import com.healthscience.rag.model.dto.AuthDTO;
import com.healthscience.rag.service.AccountService;
import com.healthscience.rag.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AccountService accountService;

    @Override
    public void register(AuthDTO.RegisterRequest request) {
        Long exists = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, request.username()));
        if (exists != null && exists > 0) {
            throw new BizException(ErrorCode.USERNAME_EXISTS, "用户名已存在");
        }

        SysUser user = new SysUser();
        user.setUsername(request.username());
        user.setPassword(passwordEncoder.encode(request.password()));
        // 昵称缺省取用户名（接口文档 §2.1）
        user.setNickname(StringUtils.hasText(request.nickname()) ? request.nickname() : request.username());
        user.setRole(LoginUser.ROLE_USER);
        user.setStatus(1);
        userMapper.insert(user);
        log.info("用户注册成功: id={}, username={}", user.getId(), user.getUsername());
    }

    @Override
    public AuthDTO.LoginResponse login(AuthDTO.LoginRequest request) {
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, request.username()));

        // 「用户不存在」与「密码错误」返回同一错误码，避免暴露账号是否已注册
        if (user == null || !passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BizException(ErrorCode.BAD_CREDENTIALS, "用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLED, "账号已被禁用，请联系管理员");
        }

        // 记录登录时间。这一步失败不应阻断登录
        try {
            SysUser updater = new SysUser();
            updater.setId(user.getId());
            updater.setLastLoginAt(LocalDateTime.now());
            userMapper.updateById(updater);
        } catch (Exception e) {
            log.warn("记录最后登录时间失败, userId={}", user.getId(), e);
        }

        String token = jwtUtil.generate(user.getId(), user.getUsername(), user.getRole());
        return new AuthDTO.LoginResponse(token, jwtUtil.expireSeconds(), AuthDTO.UserInfo.from(user));
    }

    @Override
    public void logout(Long userId) {
        // 查不到用户（已删号）静默返回：登出语义下没有可报告的调用方错误
        if (userMapper.selectById(userId) == null) {
            log.debug("登出时用户不存在，跳过失效时间线更新: userId={}", userId);
            return;
        }
        invalidateTokens(userId);
    }

    @Override
    public AuthDTO.UserProfile me(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.notFound("用户不存在");
        }
        return AuthDTO.UserProfile.from(user);
    }

    @Override
    public void deactivateSelf(Long userId, AuthDTO.DeactivateRequest request) {
        accountService.deactivateSelf(userId, request);
    }

    @Override
    public void updateProfile(Long userId, AuthDTO.UpdateProfileRequest request) {
        // MyBatis-Plus 默认仅 update 非 null 字段；三字段全空会使 SET 子句为空，
        // 生成非法 SQL 直接 500（测试报告 P3-2）—— 入口拦截为 400
        if (request.nickname() == null && request.email() == null && request.avatar() == null) {
            throw BizException.badRequest("至少提供一项要修改的内容");
        }
        // 只 set 请求中出现的字段即为「未传保持原值」
        SysUser updater = new SysUser();
        updater.setId(userId);
        if (request.nickname() != null) {
            updater.setNickname(request.nickname());
        }
        if (request.email() != null) {
            updater.setEmail(request.email());
        }
        if (request.avatar() != null) {
            updater.setAvatar(request.avatar());
        }
        userMapper.updateById(updater);
    }

    @Override
    public void updatePassword(Long userId, AuthDTO.UpdatePasswordRequest request) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.notFound("用户不存在");
        }
        if (!passwordEncoder.matches(request.oldPassword(), user.getPassword())) {
            throw new BizException(ErrorCode.WRONG_OLD_PASSWORD, "原密码错误");
        }
        SysUser updater = new SysUser();
        updater.setId(userId);
        updater.setPassword(passwordEncoder.encode(request.newPassword()));
        userMapper.updateById(updater);
        // 改密踢下线（02-接口文档 §3.2）：旧 token 全部失效，前端需引导重新登录
        invalidateTokens(userId);
        log.info("用户修改密码成功: userId={}", userId);
    }

    /** 把该用户的 token 失效时间线推进到此刻：此前签发的 token 在过滤器处一律拒绝 */
    private void invalidateTokens(Long userId) {
        SysUser updater = new SysUser();
        updater.setId(userId);
        updater.setTokenInvalidBefore(LocalDateTime.now());
        userMapper.updateById(updater);
    }
}
