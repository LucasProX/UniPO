package com.biecuoguo.state;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 多实例实现：拉黑名单放 Redis，key 自带 TTL，过期由 Redis 自己回收。
 *
 * <p>运行时 Redis 挂了会让 {@code isRevoked} 抛异常；{@code JwtAuthenticationFilter}
 * 捕获 RuntimeException 后按「未登录」处理（401）——也就是**失败时倾向踢人而不是放行**。
 * 这是刻意的：宁可短暂要求重新登录，也不要让已经退出的 token 继续可用。
 */
@Component
@ConditionalOnProperty(name = "app.state.backend", havingValue = "redis")
public class RedisRevocationStore implements RevocationStore {
    private static final String PREFIX = "unipo:revoked:";

    private final StringRedisTemplate redis;

    public RedisRevocationStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void revoke(String token, Instant expiresAt) {
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            return;
        }
        redis.opsForValue().set(PREFIX + token, "1", ttl);
    }

    @Override
    public boolean isRevoked(String token) {
        return Boolean.TRUE.equals(redis.hasKey(PREFIX + token));
    }
}
