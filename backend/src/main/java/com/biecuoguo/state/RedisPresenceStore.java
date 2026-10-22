package com.biecuoguo.state;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 多实例实现：在线状态 = Redis 里一个带 25 秒 TTL 的 key。
 *
 * <p>不再需要「过期后清理」的逻辑：心跳不再续期，key 自己就消失了。
 * 反过来说，Redis 重启会瞬间把所有人变成离线——下一次心跳（前端 10s 一次）就会恢复，能接受。
 */
@Component
@ConditionalOnProperty(name = "app.state.backend", havingValue = "redis")
public class RedisPresenceStore implements PresenceStore {
    private static final String PREFIX = "unipo:presence:";

    private final StringRedisTemplate redis;

    public RedisPresenceStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void touch(Long userId) {
        if (userId != null) {
            redis.opsForValue().set(PREFIX + userId, "1", ONLINE_WINDOW);
        }
    }

    @Override
    public void clear(Long userId) {
        if (userId != null) {
            redis.delete(PREFIX + userId);
        }
    }

    @Override
    public boolean isOnline(Long userId) {
        return userId != null && Boolean.TRUE.equals(redis.hasKey(PREFIX + userId));
    }

    @Override
    public Set<Long> batchOnlineIds(Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) { return Set.of(); }
        List<Long> ordered = userIds.stream().filter(Objects::nonNull).sorted().toList();
        if (ordered.isEmpty()) { return Set.of(); }
        List<String> values = redis.opsForValue().multiGet(ordered.stream().map(id -> PREFIX + id).toList());
        if (values == null || values.size() != ordered.size()) { return Set.of(); }
        Set<Long> online = new HashSet<>();
        for (int index = 0; index < ordered.size(); index++) {
            if (values.get(index) != null) { online.add(ordered.get(index)); }
        }
        return Set.copyOf(online);
    }
}
