package com.biecuoguo.service;

import com.biecuoguo.state.RateLimitStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * 固定窗口限流：{@code key} 在一个窗口内最多放行 {@code limit} 次。
 *
 * <p>为什么是固定窗口而不是漏桶/令牌桶：这里要挡的是「有人拿脚本刷」这种粗暴场景，
 * 不是要精确整形流量。固定窗口实现只有十几行、无后台线程、无锁竞争，
 * 代价是窗口边界处可能放过接近两倍的请求——可以接受。
 *
 * <p>计数存哪由 {@link RateLimitStore} 决定，见 {@code app.state.backend}。
 * 多实例部署必须用 Redis，否则每个实例各数一份，拦截强度按实例数打折（ISSUES Q-4）。
 */
public class RateLimitService {
    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    private final RateLimitStore store;

    public RateLimitService(RateLimitStore store) {
        this.store = store;
    }

    /**
     * 记一次请求，并告诉调用方是否放行。
     *
     * @return {@code allowed=false} 时 {@code retryAfterSeconds} 是建议的重试等待秒数
     */
    public Result acquire(String key, int limit, Duration window) {
        long windowMillis = Math.max(1, window.toMillis());
        long now = System.currentTimeMillis();
        long bucket = now / windowMillis;
        long count;
        try {
            // key 带上窗口编号：memory 实现靠它分桶，redis 实现靠它保证下个窗口换新 key。
            count = store.increment(key + '|' + bucket, window);
        } catch (RuntimeException ex) {
            // 限流是挡脚本的手段，不是安全边界。存储挂了就放行（fail-open），
            // 别让 Redis 抖动变成全站 429。这条选择写在 DEVLOG 的 Q-4 条目里。
            log.warn("限流计数失败，本次放行：{}", ex.toString());
            return new Result(true, 0);
        }
        if (count <= limit) {
            return new Result(true, 0);
        }
        long resetAt = (bucket + 1) * windowMillis;
        long retryAfter = Math.max(1, (resetAt - now + 999) / 1000);
        return new Result(false, retryAfter);
    }

    public record Result(boolean allowed, long retryAfterSeconds) {
    }
}
