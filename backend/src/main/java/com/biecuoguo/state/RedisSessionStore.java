package com.biecuoguo.state;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.List;

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
    private static final DefaultRedisScript<Long> SAVE = new DefaultRedisScript<>("""
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            redis.call('SADD', KEYS[2], ARGV[3])
            if redis.call('PTTL', KEYS[2]) < tonumber(ARGV[2]) then redis.call('PEXPIRE', KEYS[2], ARGV[2]) end
            return 1
            """, Long.class);
    private static final DefaultRedisScript<String> READ = new DefaultRedisScript<>("""
            local raw = redis.call('GET', KEYS[1])
            if not raw then return nil end
            local ok, data = pcall(cjson.decode, raw)
            if not ok or type(data) ~= 'table' or type(data.userId) ~= 'string' then redis.call('DEL', KEYS[1]); return nil end
            if tonumber(ARGV[1]) > 0 then
              local idx = ARGV[2] .. tostring(data.userId)
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
              redis.call('SADD', idx, ARGV[3])
              if redis.call('PTTL', idx) < tonumber(ARGV[1]) then redis.call('PEXPIRE', idx, ARGV[1]) end
            end
            return raw
            """, String.class);
    private static final DefaultRedisScript<Long> DELETE = new DefaultRedisScript<>("""
            local raw = redis.call('GET', KEYS[1])
            if raw then
              local ok, data = pcall(cjson.decode, raw)
              if ok and type(data.userId) == 'string' and data.userId ~= ARGV[2] then return 0 end
            end
            redis.call('DEL', KEYS[1])
            redis.call('SREM', KEYS[2], ARGV[1])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> DELETE_ALL = new DefaultRedisScript<>("""
            local ids = redis.call('SMEMBERS', KEYS[1])
            for _, id in ipairs(ids) do redis.call('DEL', ARGV[1] .. id) end
            redis.call('DEL', KEYS[1])
            return #ids
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisSessionStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(String sessionId, SessionData data, Duration ttl) {
        try {
            redis.execute(SAVE, List.of(SESSION_KEY + sessionId, USER_KEY + data.userId()),
                    objectMapper.writeValueAsString(data), String.valueOf(ttl.toMillis()), sessionId);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("会话序列化失败", e);
        }
    }

    @Override
    public Optional<SessionData> loadAndTouch(String sessionId, Duration ttl) {
        return read(sessionId, ttl.toMillis());
    }

    @Override
    public Optional<SessionData> load(String sessionId) { return read(sessionId, 0); }

    private Optional<SessionData> read(String sessionId, long ttlMillis) {
        String key = SESSION_KEY + sessionId;
        String json = redis.execute(READ, List.of(key), String.valueOf(ttlMillis), USER_KEY, sessionId);
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
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
        redis.execute(DELETE, List.of(SESSION_KEY + sessionId, USER_KEY + userId), sessionId, String.valueOf(userId));
    }

    @Override
    public void deleteAllForUser(long userId) {
        redis.execute(DELETE_ALL, List.of(USER_KEY + userId), SESSION_KEY);
    }
}
