package com.healthscience.rag.common.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JwtUtil 单测（M9/B-7）。
 */
class JwtUtilTest {

    private static final String SECRET = "unit-test-secret-0123456789abcdef";   // HS256 要求 ≥ 32 字节
    private static final String FOREIGN_SECRET = "attacker-secret-0123456789abcdef";

    private final JwtUtil jwtUtil = new JwtUtil(SECRET, 24);

    @Test
    @DisplayName("generate → parse 载荷往返：sub/username/role 一致")
    void generateAndParseRoundtrip() {
        String token = jwtUtil.generate(42L, "alice", "ADMIN");
        Claims claims = jwtUtil.parse(token);

        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.get(JwtUtil.CLAIM_USERNAME, String.class)).isEqualTo("alice");
        assertThat(claims.get(JwtUtil.CLAIM_ROLE, String.class)).isEqualTo("ADMIN");
        assertThat(claims.getId()).isNotBlank();   // jti
        // R1：毫秒级签发时间必须存在（踢下线精确比较的依据）
        assertThat(claims.get(JwtUtil.CLAIM_IAT_MS, Long.class))
                .isNotNull()
                .isLessThanOrEqualTo(System.currentTimeMillis());
    }

    @Test
    @DisplayName("非法 token 不可用且不抛出")
    void validateRejectsGarbage() {
        assertThat(jwtUtil.validate("not-a-token")).isFalse();
        assertThat(jwtUtil.validate("")).isFalse();
        assertThat(jwtUtil.validate(null)).isFalse();
    }

    @Test
    @DisplayName("他人密钥签发的 token 校验失败")
    void validateRejectsForeignSignature() {
        JwtUtil attacker = new JwtUtil(FOREIGN_SECRET, 24);
        String forged = attacker.generate(1L, "alice", "ADMIN");

        assertThat(jwtUtil.validate(forged)).isFalse();
    }

    @Test
    @DisplayName("issuedAtOf 返回签发时间（踢下线比较依据）")
    void issuedAtOfExposesIssuedAt() {
        String token = jwtUtil.generate(7L, "bob", "USER");
        assertThat(jwtUtil.issuedAtOf(token)).isNotNull();
    }
}
