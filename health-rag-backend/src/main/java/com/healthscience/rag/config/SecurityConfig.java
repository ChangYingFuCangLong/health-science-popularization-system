package com.healthscience.rag.config;

import com.healthscience.rag.common.security.JwtAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Spring Security 配置（M2 认证）。
 *
 * <p>会话策略为 STATELESS —— 纯 JWT，不依赖 HttpSession，便于后续水平扩展。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** 免认证路径。新增免认证接口时必须同步此处（Swagger 路径生产环境可经 springdoc 配置关闭） */
    private static final String[] PERMIT_ALL = {
            "/api/auth/register",
            "/api/auth/login",
            "/api/ping",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // SSE 流式响应结束时 Tomcat 会发起一次 ASYNC dispatch 把缓冲内容写回客户端，
                        // 该阶段 OncePerRequestFilter 默认跳过 JWT 解析、SecurityContext 已清空，
                        // 若不放行会触发 AuthorizationDeniedException 并掐断连接（浏览器收不到任何 token）。
                        // ASYNC 阶段只是回写已产出的结果，不再做业务鉴权；权限控制在正常请求阶段已执行。
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .requestMatchers(PERMIT_ALL).permitAll()
                        // 管理端权限由 Security 链统一强制（JwtAuthenticationFilter 已写入 ROLE_ 前缀权限），
                        // Controller 不再各自校验 —— 新增管理端接口自动被覆盖，不会因漏写检查而越权
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                // JWT 过滤器必须早于表单登录过滤器，否则会被默认的认证流程拦下
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) ->
                                writeJson(res, 401, "未登录或 token 失效"))
                        .accessDeniedHandler((req, res, ex) ->
                                writeJson(res, 403, "无访问权限")))
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 认证 / 授权失败时返回与 {@code R} 一致的 JSON 结构。
     *
     * <p>若沿用 Spring Security 的默认错误体，前端就不得不多处理一种响应格式。
     */
    private static void writeJson(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(code);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":" + code + ",\"message\":\"" + message + "\",\"data\":null}");
    }
}
