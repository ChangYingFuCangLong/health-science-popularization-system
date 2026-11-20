package com.healthscience.rag.common.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * JWT 密钥守卫（测试报告 B5 方案 1）。
 *
 * <p>默认开发密钥是公开的（仓库可查），用它签发的 token 可伪造任意身份。
 * 本守卫把「必须注入真实密钥」变成结构约束：
 * <ul>
 *   <li>生产 profile（含 prod 的任意激活 profile）+ 默认密钥 → <b>拒绝启动</b>；</li>
 *   <li>开发环境使用默认密钥 → 放行但打 ERROR 级横幅，演示/答辩环境不误伤。</li>
 * </ul>
 * 配合过滤器「角色从 DB 读取」（B5 方案 2）构成双保险：即使密钥泄漏，
 * 伪造 token 也只能冒用身份、无法越权。
 */
@Slf4j
@Component
public class JwtSecretGuard {

    private static final String DEV_SECRET_PREFIX = "health-rag-backend-dev-secret-override-by-env-";

    public JwtSecretGuard(@Value("${jwt.secret}") String secret, Environment environment) {
        if (!secret.startsWith(DEV_SECRET_PREFIX)) {
            return;   // 已注入自定义密钥
        }
        boolean prod = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(p -> p.toLowerCase().contains("prod"));
        if (prod) {
            throw new IllegalStateException(
                    "生产环境禁止使用内置开发 JWT 密钥：请通过环境变量 JWT_SECRET 注入真实密钥（≥32 字节）");
        }
        log.error("==============================================================");
        log.error("████ 当前使用内置开发 JWT 密钥：任何拿到代码的人都可伪造任意身份的 token。");
        log.error("████ 开发环境可忽略；部署/公网环境必须设置环境变量 JWT_SECRET。");
        log.error("==============================================================");
    }
}
