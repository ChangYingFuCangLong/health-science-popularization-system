package com.healthscience.rag.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 工具类（jjwt 0.12.x）。
 *
 * <p>载荷包含：{@code jti}（供登出黑名单使用）、{@code sub}（用户 ID）、
 * {@code username}、{@code role}。
 */
@Component
public class JwtUtil {

    public static final String CLAIM_USERNAME = "username";
    public static final String CLAIM_ROLE = "role";
    /**
     * 毫秒级签发时间（复测报告 R1）：RFC 7519 的 iat 只有秒级精度，
     * 而踢下线比较需要毫秒——同一秒内「签发→登出/改密/禁用」的 token
     * 用 iat 判断永远漏杀。iatMs 缺失（历史 token）时回落 iat。
     */
    public static final String CLAIM_IAT_MS = "iatMs";

    private final SecretKey key;
    private final long expireSeconds;

    public JwtUtil(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expire-hours:24}") long expireHours) {
        // HS256 要求密钥不少于 256 bit（32 字节），过短会在此处直接抛错
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireSeconds = expireHours * 3600;
    }

    /** 生成 token */
    public String generate(Long userId, String username, String role) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(userId))
                .claim(CLAIM_USERNAME, username)
                .claim(CLAIM_ROLE, role)
                .claim(CLAIM_IAT_MS, now)
                .issuedAt(new Date(now))
                .expiration(new Date(now + expireSeconds * 1000))
                .signWith(key)
                .compact();
    }

    /** 解析载荷；token 非法或过期时抛 {@link JwtException} */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** 校验 token 是否可用（不抛异常） */
    public boolean validate(String token) {
        try {
            parse(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /** 有效期（秒），对应登录响应中的 expiresIn */
    public long expireSeconds() {
        return expireSeconds;
    }

    /** token 的签发时间。登出/改密/禁用踢下线用它与 {@code sys_user.token_invalid_before} 比较 */
    public Date issuedAtOf(String token) {
        return parse(token).getIssuedAt();
    }

    /** 供登出黑名单使用：取 token 的 jti */
    public String jtiOf(String token) {
        return parse(token).getId();
    }
}
