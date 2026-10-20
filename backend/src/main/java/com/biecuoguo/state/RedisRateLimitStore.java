package com.biecuoguo.state;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 多实例实现：INCR + EXPIRE，所有实例共用一个计数器。
 *
 * <p>INCR 是原子的，所以不会出现「两个实例各自数到 29，一共放过 58 次」那种事。
 * EXPIRE 只在第一次（INCR 返回 1）时设，之后由 Redis 保着；万一 EXPIRE 那一步丢了，
 * key 会永久留着——所以调用方给的 key 里带了窗口编号，下一个窗口是新 key，不会越积越死。
 */
@Component
@ConditionalOnProperty(name = "app.state.backend", havingValue = "redis")
public class RedisRateLimitStore implements RateLimitStore {
    private static final String PREFIX = "unipo:rl:";

    private final StringRedisTemplate redis;

    public RedisRateLimitStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public long increment(String key, Duration window) {
        String fullKey = PREFIX + key;
        Long count = redis.opsForValue().increment(fullKey);
        if (count != null && count == 1L) {
            redis.expire(fullKey, window);
        }
        return count == null ? 0L : count;
    }
}
