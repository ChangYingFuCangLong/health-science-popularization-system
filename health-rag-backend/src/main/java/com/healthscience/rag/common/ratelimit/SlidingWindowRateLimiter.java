package com.healthscience.rag.common.ratelimit;

import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 滑动窗口限流器（内存实现，线程安全）。
 *
 * <p>由 {@code WebMvcConfig} 按 {@code health.security.rate-limit.*} 配置构造
 * （chat/auth 两档各一例），不参与组件扫描。
 *
 * <p><b>为什么不用 Redis / Bucket4j</b>：毕设单实例、限流档位只有两个
 * （流式问答 10 次/分钟/用户、认证接口 5 次/分钟/IP），一个滑动窗口足够；
 * 引入中间件或新依赖的运维成本远超收益（与 ADR-008「不引 Redis」同一取舍）。
 *
 * <p><b>内存回收</b>：key 数量随用户/IP 增长，这里做机会式清理 ——
 * 每隔若干次申请全表扫一遍，删除窗口内已无任何命中记录的 key，避免长期运行膨胀。
 * 单个 key 内的过期命中则在每次申请时从头淘汰。
 *
 * <p><b>时间来源可注入</b>：{@link #tryAcquire(String, long)} 接收毫秒时间戳而非取系统时钟，
 * 单元测试可以自由推进时间验证窗口滑动，无需 sleep。
 */
public class SlidingWindowRateLimiter {

    /** 窗口长度（毫秒），默认 1 分钟 */
    private final long windowMs;
    /** 窗口内允许的最大次数 */
    private final int limit;
    /** key -> 该 key 窗口内的命中时间戳队列（升序） */
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();
    /** 机会式全表清理的触发间隔 */
    private static final long SWEEP_EVERY = 4096;
    private final AtomicLong acquireCount = new AtomicLong();

    public SlidingWindowRateLimiter(int limit, long windowMs) {
        this.limit = limit;
        this.windowMs = windowMs;
    }

    /**
     * 申请一次配额。
     *
     * @param key   限流主体（如 {@code chat:1} / {@code auth:1.2.3.4}）
     * @param nowMs 当前毫秒时间戳
     * @return true = 放行；false = 超限，调用方应返回 429
     */
    public boolean tryAcquire(String key, long nowMs) {
        maybeSweep(nowMs);
        Deque<Long> deque = hits.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        evictExpired(deque, nowMs);
        if (deque.size() >= limit) {
            return false;
        }
        deque.addLast(nowMs);
        return true;
    }

    /** 该 key 当前窗口内的剩余可用次数（超限响应头 X-RateLimit-Remaining 用） */
    public int remaining(String key, long nowMs) {
        Deque<Long> deque = hits.get(key);
        if (deque == null) {
            return limit;
        }
        evictExpired(deque, nowMs);
        return Math.max(0, limit - deque.size());
    }

    private void evictExpired(Deque<Long> deque, long nowMs) {
        long threshold = nowMs - windowMs;
        while (!deque.isEmpty() && deque.peekFirst() <= threshold) {
            deque.pollFirst();
        }
    }

    /** 机会式清理：删除窗口内已无命中的 key，防止 key 集合无限增长 */
    private void maybeSweep(long nowMs) {
        if (acquireCount.incrementAndGet() % SWEEP_EVERY != 0) {
            return;
        }
        long threshold = nowMs - windowMs;
        Iterator<Map.Entry<String, Deque<Long>>> it = hits.entrySet().iterator();
        while (it.hasNext()) {
            Deque<Long> deque = it.next().getValue();
            evictExpired(deque, nowMs);
            if (deque.isEmpty()) {
                it.remove();
            }
        }
    }
}
