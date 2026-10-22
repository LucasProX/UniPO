package com.biecuoguo.message.internal;

import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** Separate from worker claims; administrative retries can only leave the FAILED state. */
public interface OutboxOperationsMapper {
    @Select("""
            <script>
            SELECT id, event_id, message_id, channel, state, attempts, last_error,
                   created_at, available_at, lease_until, completed_at
            FROM message_delivery_outbox
            WHERE state = #{state}
              <if test="channel != null">AND channel = #{channel}</if>
              <if test="beforeId != null">AND id &lt; #{beforeId}</if>
            ORDER BY id DESC
            LIMIT #{limit}
            </script>
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @ConstructorArgs({
            @Arg(column = "id", javaType = long.class),
            @Arg(column = "event_id", javaType = String.class),
            @Arg(column = "message_id", javaType = long.class),
            @Arg(column = "channel", javaType = String.class),
            @Arg(column = "state", javaType = String.class),
            @Arg(column = "attempts", javaType = int.class),
            @Arg(column = "last_error", javaType = String.class),
            @Arg(column = "created_at", javaType = LocalDateTime.class),
            @Arg(column = "available_at", javaType = LocalDateTime.class),
            @Arg(column = "lease_until", javaType = LocalDateTime.class),
            @Arg(column = "completed_at", javaType = LocalDateTime.class)
    })
    List<DeliveryRow> page(@Param("state") String state, @Param("channel") String channel,
                           @Param("beforeId") Long beforeId, @Param("limit") int limit);

    @Select("""
            SELECT id, event_id, message_id, channel, state, attempts, last_error,
                   created_at, available_at, lease_until, completed_at
            FROM message_delivery_outbox WHERE id = #{id} FOR UPDATE
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @ConstructorArgs({
            @Arg(column = "id", javaType = long.class),
            @Arg(column = "event_id", javaType = String.class),
            @Arg(column = "message_id", javaType = long.class),
            @Arg(column = "channel", javaType = String.class),
            @Arg(column = "state", javaType = String.class),
            @Arg(column = "attempts", javaType = int.class),
            @Arg(column = "last_error", javaType = String.class),
            @Arg(column = "created_at", javaType = LocalDateTime.class),
            @Arg(column = "available_at", javaType = LocalDateTime.class),
            @Arg(column = "lease_until", javaType = LocalDateTime.class),
            @Arg(column = "completed_at", javaType = LocalDateTime.class)
    })
    DeliveryRow lock(@Param("id") long id);

    @Update("""
            UPDATE message_delivery_outbox
            SET state = 'PENDING', available_at = UTC_TIMESTAMP(6), completed_at = NULL,
                claim_token = NULL, lease_until = NULL
            WHERE id = #{id} AND state = 'FAILED' AND attempts = #{expectedAttempts}
            """)
    int retryFailed(@Param("id") long id, @Param("expectedAttempts") int expectedAttempts);

    record DeliveryRow(long id, String eventId, long messageId, String channel, String state,
                       int attempts, String lastError, LocalDateTime createdAt,
                       LocalDateTime availableAt, LocalDateTime leaseUntil, LocalDateTime completedAt) {}
}
