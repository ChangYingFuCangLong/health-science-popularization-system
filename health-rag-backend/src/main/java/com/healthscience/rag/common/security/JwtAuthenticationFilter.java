package com.healthscience.rag.common.security;

import com.healthscience.rag.entity.SysUser;
import com.healthscience.rag.mapper.SysUserMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

/**
 * JWT 认证过滤器：从 {@code Authorization: Bearer <token>} 解析身份并写入安全上下文。
 *
 * <p>token 缺失或非法时<b>不抛异常</b>，直接放行 —— 由 {@code SecurityConfig} 的
 * 授权规则与 {@code authenticationEntryPoint} 决定最终返回 401。
 *
 * <p><b>token 失效时间线校验（M9/B-4）</b>：签名合法但签发时间早于
 * {@code sys_user.token_invalid_before} 的 token 一律不认证 —— 这是
 * 登出 / 改密 / 禁用「立即踢下线」的统一实现（替代 Redis 黑名单）。
 * 校验顺带覆盖「用户已被逻辑删除」：selectById 走 @TableLogic，删号后 token 即失效。
 *
 * <p><b>性能取舍</b>：每个带 token 的请求多一次用户主键查询。毕设单实例、QPS 极低，
 * 换来踢下线的强一致，划算；若将来上多实例再评估缓存。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;
    private final SysUserMapper userMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null && jwtUtil.validate(token)) {
            SysUser user = userMapper.selectById(Long.valueOf(jwtUtil.parse(token).getSubject()));
            // 角色一律以 DB 为准（测试报告 B5）：签名合法但角色声明不可信——
            // 伪造 token 最多冒用身份，无法获得 ADMIN 越权；角色变更也随之即时生效。
            // 用户名同理取 DB 值，避免改名后 token 内残留旧名。
            if (user != null && notInvalidated(token, user)) {
                LoginUser loginUser = new LoginUser(user.getId(), user.getUsername(), user.getRole());
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        loginUser, null, List.of(new SimpleGrantedAuthority("ROLE_" + loginUser.role())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
                UserContext.set(loginUser);
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            // 虚拟线程会被复用，必须清理，否则会串号
            UserContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * token 签发时间不早于其 token_invalid_before（未设置则视为不失效）。
     *
     * <p><b>毫秒级比较（复测报告 R1）</b>：第一轮修复把失效时间截断到秒，消除了
     * 「同秒重登被误杀」，却引入反向回归——同一秒内「签发→登出/禁用」的旧 token
     * 永远漏杀。最终方案：JWT 携带毫秒级 {@code iatMs} claim，与毫秒级
     * invalid_before 直接比较，误杀与漏杀两个方向都精确。
     * 历史无 iatMs 的 token 回落 iat（秒级），边界退化为旧行为直至自然过期。
     */
    private boolean notInvalidated(String token, SysUser user) {
        LocalDateTime invalidBefore = user.getTokenInvalidBefore();
        if (invalidBefore == null) {
            return true;
        }
        Claims claims = jwtUtil.parse(token);
        Long iatMs = claims.get(JwtUtil.CLAIM_IAT_MS, Long.class);
        long issuedAtMs = iatMs != null
                ? iatMs
                : jwtUtil.issuedAtOf(token).getTime();   // 旧 token 兼容回落
        long thresholdMs = invalidBefore.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return issuedAtMs >= thresholdMs;
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
