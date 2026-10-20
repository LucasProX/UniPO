package com.biecuoguo.state;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * Redis 会话存储。key 设计：
 *
 * <pre>
 * unipo:session:{sessionId}        -> JSON {userId,role,email,csrfToken}，带 TTL
 * unipo:session:user:{userId}      -> set&lt;sessionId&gt;，用于「踢掉所有设备」
 * </pre>
 *
 * <p>读取时续期（滑动过期）：用户一直在用就不会突然掉线；连续 7 天不活跃才失效。
 * 序列化用 Jackson，别自己拼分隔符 —— email 里出现分隔符就会把数据拼坏。
 */
@Component
@ConditionalOnProperty(name = "app.state.backend", havingValue = "redis")
public class RedisSessionStore implements SessionStore {

    private static final String SESSION_KEY = "unipo:session:";
    private static final String USER_KEY = "unipo:session:user:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisSessionStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(String sessionId, SessionData data, Duration ttl) {
        try {
            redis.opsForValue().set(SESSION_KEY + sessionId, objectMapper.writeValueAsString(data), ttl);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("会话序列化失败", e);
        }
        String userKey = USER_KEY + data.userId();
        redis.opsForSet().add(userKey, sessionId);
        redis.expire(userKey, ttl);
    }

    @Override
    public Optional<SessionData> loadAndTouch(String sessionId, Duration ttl) {
        String key = SESSION_KEY + sessionId;
        String json = redis.opsForValue().get(key);
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        redis.expire(key, ttl);
        try {
            return Optional.of(objectMapper.readValue(json, SessionData.class));
        } catch (JsonProcessingException e) {
            // 数据坏了就当没登录，顺手删掉，别让它一直报错
            redis.delete(key);
            return Optional.empty();
        }
    }

    @Override
    public void delete(String sessionId, long userId) {
        redis.delete(SESSION_KEY + sessionId);
        redis.opsForSet().remove(USER_KEY + userId, sessionId);
    }

    @Override
    public void deleteAllForUser(long userId) {
        String userKey = USER_KEY + userId;
        Set<String> ids = redis.opsForSet().members(userKey);
        if (ids != null) {
            for (String id : ids) {
                redis.delete(SESSION_KEY + id);
            }
        }
        redis.delete(userKey);
    }
}
