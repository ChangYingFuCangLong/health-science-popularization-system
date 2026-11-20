package com.healthscience.rag.common.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 滑动窗口限流器单测（M9/B-3）。
 *
 * <p>时间由测试注入（tryAcquire 的 nowMs 参数），不依赖系统时钟、无需 sleep。
 */
class SlidingWindowRateLimiterTest {

    private static final long MINUTE = 60_000L;

    @Test
    @DisplayName("窗口内允许的次数不超过 limit，第 limit+1 次拒绝")
    void blocksOverLimit() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(3, MINUTE);
        assertThat(limiter.tryAcquire("k", 0)).isTrue();
        assertThat(limiter.tryAcquire("k", 1)).isTrue();
        assertThat(limiter.tryAcquire("k", 2)).isTrue();
        assertThat(limiter.tryAcquire("k", 3)).isFalse();
    }

    @Test
    @DisplayName("窗口滑动：最早的命中出窗后配额恢复")
    void windowSlides() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(2, MINUTE);
        assertThat(limiter.tryAcquire("k", 0)).isTrue();
        assertThat(limiter.tryAcquire("k", 1)).isTrue();
        // 窗口未滑出（t=0 的命中在 now - window = 0 时才过期）
        assertThat(limiter.tryAcquire("k", MINUTE - 1)).isFalse();
        // t=MINUTE 时阈值 = 0，t=0 的命中被淘汰，腾出 1 个配额
        assertThat(limiter.tryAcquire("k", MINUTE)).isTrue();
        // t=MINUTE+2 阈值 = 2，两条早期命中全部淘汰，配额完全恢复
        assertThat(limiter.tryAcquire("k", MINUTE + 2)).isTrue();
    }

    @Test
    @DisplayName("remaining 反映窗口内剩余配额")
    void remainingReflectsQuota() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(3, MINUTE);
        limiter.tryAcquire("k", 0);
        limiter.tryAcquire("k", 1);
        assertThat(limiter.remaining("k", 2)).isEqualTo(1);
        assertThat(limiter.remaining("unknown", 2)).isEqualTo(3);
    }

    @Test
    @DisplayName("不同 key 配额独立")
    void keysAreIndependent() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(1, MINUTE);
        assertThat(limiter.tryAcquire("a", 0)).isTrue();
        assertThat(limiter.tryAcquire("a", 1)).isFalse();
        assertThat(limiter.tryAcquire("b", 2)).isTrue();
    }
}
