package com.healthscience.rag.config;

import com.healthscience.rag.common.ratelimit.RateLimitInterceptor;
import com.healthscience.rag.common.ratelimit.SlidingWindowRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册限流拦截器（任务卡 B-3）。
 *
 * <p>档位与阈值来自 {@code health.security.rate-limit.*}（接口文档 02 §1.5）：
 * chat 档作用于流式问答与语音识别（按用户），auth 档作用于注册/登录（按 IP）。
 */
@Configuration
public class WebMvcConfig {

    @Bean
    public SlidingWindowRateLimiter chatRateLimiter(
            @Value("${health.security.rate-limit.chat-per-minute:10}") int chatPerMinute) {
        return new SlidingWindowRateLimiter(chatPerMinute, 60_000L);
    }

    @Bean
    public SlidingWindowRateLimiter authRateLimiter(
            @Value("${health.security.rate-limit.auth-per-minute:5}") int authPerMinute) {
        return new SlidingWindowRateLimiter(authPerMinute, 60_000L);
    }

    @Bean
    public RateLimitInterceptor rateLimitInterceptor(SlidingWindowRateLimiter chatRateLimiter,
                                                     SlidingWindowRateLimiter authRateLimiter) {
        return new RateLimitInterceptor(chatRateLimiter, authRateLimiter);
    }

    @Bean
    public WebMvcConfigurer rateLimitConfigurer(RateLimitInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor)
                        .addPathPatterns("/api/chat/stream", "/api/chat/asr",
                                "/api/auth/register", "/api/auth/login");
            }
        };
    }
}
