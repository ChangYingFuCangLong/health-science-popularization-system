package com.healthscience.rag.common.ratelimit;

import com.healthscience.rag.common.security.LoginUser;
import com.healthscience.rag.common.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 接口限流拦截器（任务卡 B-3）。
 *
 * <p><b>档位</b>（契约 02 §1.5）：
 * <ul>
 *   <li>{@code chat} 档：{@code POST /api/chat/stream} 与 {@code POST /api/chat/asr}，
 *       按登录用户 ID 计数 —— 未登录请求不计数，放行给 Security 链回 401；</li>
 *   <li>{@code auth} 档：{@code /api/auth/register} 与 {@code /api/auth/login}，
 *       按客户端 IP 计数（X-Forwarded-For 优先，反代部署下取真实客户端）。</li>
 * </ul>
 *
 * <p><b>执行时机</b>：HandlerInterceptor 晚于 Security 过滤器链执行，因此 chat 档
 * 在这里能读到 {@link UserContext}；auth 档接口本就免认证，直接按 IP。
 *
 * <p><b>超限响应</b>：HTTP 429 + 与 {@code R} 一致的 JSON 结构 +
 * {@code X-RateLimit-Remaining: 0}，格式对齐 SecurityConfig 的 401/403 错误体。
 */
@Slf4j
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    /** 429 错误文案，与 02-接口文档.md §1.3 一致 */
    private static final String MESSAGE = "请求过于频繁，请稍后再试";

    private final SlidingWindowRateLimiter chatLimiter;
    private final SlidingWindowRateLimiter authLimiter;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String path = request.getRequestURI();
        boolean chatTier = path.endsWith("/api/chat/stream") || path.endsWith("/api/chat/asr");
        String key = chatTier ? chatKey(request) : authKey(request);
        // 未登录（key 为 null）不计数：认证失败由 Security 链回 401，限流器不消耗配额
        if (key == null) {
            return true;
        }
        SlidingWindowRateLimiter limiter = chatTier ? chatLimiter : authLimiter;

        long now = System.currentTimeMillis();
        if (limiter.tryAcquire(key, now)) {
            return true;
        }
        log.warn("[限流] {} {} 超限, key={}", request.getMethod(), path, key);
        response.setStatus(429);
        response.setHeader("X-RateLimit-Remaining", "0");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":429,\"message\":\"" + MESSAGE + "\",\"data\":null}");
        return false;
    }

    /**
     * chat 档按用户计数；未登录返回 null（不计数）——
     * 认证失败由 Security 链处理，限流器不为无效请求消耗配额。
     */
    private String chatKey(HttpServletRequest request) {
        LoginUser user = UserContext.get();
        return user == null ? null : "chat:" + user.id();
    }

    /** auth 档按客户端 IP 计数，X-Forwarded-For 优先（Nginx 反代场景取第一个即真实客户端） */
    private String authKey(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        String ip = xff == null || xff.isBlank()
                ? request.getRemoteAddr()
                : xff.split(",")[0].trim();
        return "auth:" + ip;
    }
}
