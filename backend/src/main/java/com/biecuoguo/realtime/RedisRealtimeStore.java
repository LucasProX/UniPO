package com.biecuoguo.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Redis 版续传：每用户一条 Stream（MAXLEN ~ 500），全局序号用 INCR。
 *
 * <p>为什么不把事件塞进 String key：Stream 天然按 ID 排序、支持范围读取，
 * 而且 XADD 是原子的，多实例同时写不会互相覆盖。
 */
@Component
@ConditionalOnProperty(name = "app.state.backend", havingValue = "redis")
public class RedisRealtimeStore implements RealtimeStore {

    private static final String SEQ_KEY = "unipo:rt:seq";
    private static final String STREAM_PREFIX = "unipo:rt:stream:";
    private static final long MAXLEN = 500L;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisRealtimeStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public long nextSeq() {
        Long value = redis.opsForValue().increment(SEQ_KEY);
        return value == null ? 0L : value;
    }

    @Override
    public void append(Long userId, RealtimeEvent event) {
        String key = STREAM_PREFIX + userId;
        try {
            String json = objectMapper.writeValueAsString(event);
            redis.opsForStream().add(StreamRecords.newRecord().in(key).ofMap(Map.of("e", json)));
            redis.opsForStream().trim(key, MAXLEN);
        } catch (Exception e) {
            // 续传是增强能力，写失败不能让发消息本身失败
            org.slf4j.LoggerFactory.getLogger(RedisRealtimeStore.class)
                    .warn("实时事件落 Stream 失败 user={}: {}", userId, e.getMessage());
        }
    }

    @Override
    public List<RealtimeEvent> after(Long userId, long afterSeq, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        String key = STREAM_PREFIX + userId;
        // Redis Stream IDs are generated from Redis time, not from RealtimeEvent.seq.
        // Read the bounded retained stream, then apply the application sequence cursor.
        Range<String> range = Range.from(Range.Bound.<String>unbounded()).to(Range.Bound.<String>unbounded());
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().range(key, range);
        if (records == null) {
            return List.of();
        }
        List<RealtimeEvent> result = new ArrayList<>();
        for (MapRecord<String, Object, Object> record : records) {
            Object raw = record.getValue().get("e");
            if (raw == null) {
                continue;
            }
            try {
                RealtimeEvent event = objectMapper.readValue(raw.toString(), RealtimeEvent.class);
                if (event.seq() > afterSeq) {
                    result.add(event);
                }
            } catch (Exception ignored) {
                // 单条坏数据跳过，别让整次续传失败
            }
        }
        return result.stream().sorted(Comparator.comparingLong(RealtimeEvent::seq)).limit(limit).toList();
    }
}
