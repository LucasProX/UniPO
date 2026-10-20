package com.biecuoguo.state;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内固定窗口计数器。
 *
 * <p>多实例部署时每个实例各算一份，拦截强度按实例数打折（见 ISSUES Q-4）；
 * 单实例阶段够用，而且不需要外部依赖。
 */
@Component
@ConditionalOnProperty(name = "app.state.backend", havingValue = "memory", matchIfMissing = true)
public class MemoryRateLimitStore implements RateLimitStore {
    /** 超过这个 key 数量就清一次过期窗口，防止被构造大量 key 打爆内存。 */
    private static final int MAX_KEYS = 20_000;

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    @Override
    public long increment(String key, Duration window) {
        long now = System.currentTimeMillis();
        long windowMillis = Math.max(1, window.toMillis());
        evictExpired(windowMillis);
        Counter counter = counters.compute(key, (k, existing) -> {
            if (existing == null || now - existing.startMillis >= windowMillis) {
                return new Counter(now);
            }
            existing.count++;
            return existing;
        });
        return counter.count;
    }

    private void evictExpired(long windowMillis) {
        if (counters.size() < MAX_KEYS) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Counter>> it = counters.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().startMillis >= windowMillis) {
                it.remove();
            }
        }
    }

    private static final class Counter {
        private final long startMillis;
        private long count = 1;

        private Counter(long startMillis) {
            this.startMillis = startMillis;
        }
    }
}
