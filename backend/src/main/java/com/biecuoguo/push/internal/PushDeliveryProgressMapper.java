package com.biecuoguo.push.internal;

import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface PushDeliveryProgressMapper {
    @Insert("""
            INSERT INTO push_delivery_batches
                (event_id, user_id, snapshot_max_token_id, next_after_token_id, created_at)
            SELECT #{eventId}, #{userId}, COALESCE(MAX(id), 0), 0, UTC_TIMESTAMP(6)
            FROM device_tokens WHERE user_id = #{userId}
            ON DUPLICATE KEY UPDATE event_id = push_delivery_batches.event_id
            """)
    int open(@Param("eventId") String eventId, @Param("userId") long userId);

    @Select("""
            SELECT event_id, user_id, snapshot_max_token_id, next_after_token_id
            FROM push_delivery_batches WHERE event_id = #{eventId} FOR UPDATE
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @ConstructorArgs({
            @Arg(column = "event_id", javaType = String.class),
            @Arg(column = "user_id", javaType = long.class),
            @Arg(column = "snapshot_max_token_id", javaType = long.class),
            @Arg(column = "next_after_token_id", javaType = long.class)
    })
    BatchRow lockBatch(@Param("eventId") String eventId);

    @Select("""
            SELECT event_id, user_id, snapshot_max_token_id, next_after_token_id
            FROM push_delivery_batches WHERE event_id = #{eventId}
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @ConstructorArgs({
            @Arg(column = "event_id", javaType = String.class),
            @Arg(column = "user_id", javaType = long.class),
            @Arg(column = "snapshot_max_token_id", javaType = long.class),
            @Arg(column = "next_after_token_id", javaType = long.class)
    })
    BatchRow batch(@Param("eventId") String eventId);

    @Select("""
            SELECT d.id, d.token
            FROM push_delivery_batches b JOIN device_tokens d ON d.user_id = b.user_id
            WHERE b.event_id = #{eventId} AND b.user_id = #{userId}
              AND d.id <= b.snapshot_max_token_id AND d.id > #{afterId}
              AND NOT EXISTS (SELECT 1 FROM push_delivery_receipts r
                              WHERE r.event_id = b.event_id AND r.device_token_id = d.id)
            ORDER BY d.id LIMIT #{limit}
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @ConstructorArgs({
            @Arg(column = "id", javaType = long.class),
            @Arg(column = "token", javaType = String.class)
    })
    List<PushDeliveryProgress.Target> pending(@Param("eventId") String eventId, @Param("userId") long userId,
                                              @Param("afterId") long afterId, @Param("limit") int limit);

    @Update("""
            UPDATE push_delivery_batches SET next_after_token_id = #{afterId}
            WHERE event_id = #{eventId} AND user_id = #{userId}
            """)
    int advance(@Param("eventId") String eventId, @Param("userId") long userId, @Param("afterId") long afterId);

    @Select("""
            SELECT EXISTS (
              SELECT 1 FROM push_delivery_batches b JOIN device_tokens d ON d.user_id = b.user_id
              WHERE b.event_id = #{eventId} AND b.user_id = #{userId} AND d.id <= b.snapshot_max_token_id
                AND NOT EXISTS (SELECT 1 FROM push_delivery_receipts r
                                WHERE r.event_id = b.event_id AND r.device_token_id = d.id)
            )
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    boolean hasPending(@Param("eventId") String eventId, @Param("userId") long userId);

    @Select("""
            SELECT EXISTS (
              SELECT 1 FROM push_delivery_batches b JOIN device_tokens d ON d.user_id = b.user_id
              WHERE b.event_id = #{eventId} AND b.user_id = #{userId}
                AND d.id = #{deviceId} AND d.token = #{token} AND d.id <= b.snapshot_max_token_id
                AND NOT EXISTS (SELECT 1 FROM push_delivery_receipts r
                                WHERE r.event_id = b.event_id AND r.device_token_id = d.id)
            )
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    boolean isCurrentTarget(@Param("eventId") String eventId, @Param("userId") long userId,
                            @Param("deviceId") long deviceId, @Param("token") String token);

    @Insert("""
            INSERT INTO push_delivery_receipts (event_id, device_token_id, outcome, created_at)
            SELECT event_id, #{deviceId}, #{outcome}, UTC_TIMESTAMP(6)
            FROM push_delivery_batches
            WHERE event_id = #{eventId} AND user_id = #{userId}
              AND #{deviceId} > 0 AND #{deviceId} <= snapshot_max_token_id
            ON DUPLICATE KEY UPDATE event_id = push_delivery_receipts.event_id
            """)
    int receipt(@Param("eventId") String eventId, @Param("userId") long userId,
                @Param("deviceId") long deviceId, @Param("outcome") String outcome);

    record BatchRow(String eventId, long userId, long snapshotMaxTokenId, long nextAfterTokenId) {}
}
