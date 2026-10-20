package com.biecuoguo.service;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 固定窗口限流：{@code key} 在一个窗口内最多放行 {@code limit} 次。
 *
 * <p>为什么是固定窗口而不是漏桶/令牌桶：这里要挡的是「有人拿脚本刷」这种粗暴场景，
 * 不是要精确整形流量。固定窗口实现只有十几行、无后台线程、无锁竞争，
 * 代价是窗口边界处可能放过接近两倍的请求——可以接受。
 *
 * <p>为什么放在进程内存里：单实例阶段够用，而且不需要引入 Redis。
 * 多实例部署后每个实例各算一份，拦截强度会打折扣（见 docs/ISSUES.md 的 Q-4）。
 */
public class RateLimitService {

    /** 超过这个 key 数量就清一次过期窗口，防止被构造大量 key 打爆内存。 */
    private static final int MAX_KEYS = 20_000;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /**
     * 记一次请求，并告诉调用方是否放行。
     *
     * @return {@code allowed=false} 时 {@code retryAfterSeconds} 是建议的重试等待秒数
     */
    public Result acquire(String key, int limit, Duration window) {
        long now = System.currentTimeMillis();
        long windowMillis = Math.max(1, window.toMillis());
        evictExpired(windowMillis);
        Window current = windows.compute(key, (k, existing) -> {
            if (existing == null || now - existing.startMillis >= windowMillis) {
                return new Window(now);
            }
            existing.count++;
            return existing;
        });
        if (current.count <= limit) {
            return new Result(true, 0);
        }
        long retryAfter = Math.max(1, (current.startMillis + windowMillis - now + 999) / 1000);
        return new Result(false, retryAfter);
    }

    /** 清掉已经过期的窗口。只在 key 数量超标时才真正遍历，正常路径上是一次 size 判断。 */
    private void evictExpired(long windowMillis) {
        if (windows.size() < MAX_KEYS) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Window>> it = windows.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().startMillis >= windowMillis) {
                it.remove();
            }
        }
    }

    public record Result(boolean allowed, long retryAfterSeconds) {
    }

    private static final class Window {
        private final long startMillis;
        private int count = 1;

        private Window(long startMillis) {
            this.startMillis = startMillis;
        }
    }
}
