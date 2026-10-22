package com.biecuoguo.message.internal;

import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** Persistent delivery metadata only; message bodies remain in the message fact table. */
public interface MessageOutboxMapper {

    @Insert("""
            INSERT INTO message_delivery_outbox (event_id, message_id, channel, available_at, created_at)
            VALUES (#{eventId}, #{messageId}, #{channel}, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE id = id
            """)
    int enqueue(@Param("eventId") String eventId, @Param("messageId") long messageId,
                @Param("channel") String channel);

    @Select("""
            SELECT id, event_id, message_id, channel, attempts
            FROM message_delivery_outbox
            WHERE (state = 'PENDING' AND available_at <= UTC_TIMESTAMP(6))
               OR (state = 'SENDING' AND lease_until <= UTC_TIMESTAMP(6))
            ORDER BY available_at, id
            LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @ConstructorArgs({
            @Arg(column = "id", javaType = long.class),
            @Arg(column = "event_id", javaType = String.class),
            @Arg(column = "message_id", javaType = long.class),
            @Arg(column = "channel", javaType = String.class),
            @Arg(column = "attempts", javaType = int.class)
    })
    List<ReadyDelivery> ready(@Param("limit") int limit);

    @Update("""
            UPDATE message_delivery_outbox
            SET state = 'SENDING', claim_token = #{claimToken},
                lease_until = TIMESTAMPADD(MICROSECOND, #{leaseMicros}, UTC_TIMESTAMP(6)),
                attempts = LEAST(attempts + 1, 2147483647)
            WHERE id = #{id}
              AND ((state = 'PENDING' AND available_at <= UTC_TIMESTAMP(6))
                OR (state = 'SENDING' AND lease_until <= UTC_TIMESTAMP(6)))
            """)
    int claim(@Param("id") long id, @Param("claimToken") String claimToken,
              @Param("leaseMicros") long leaseMicros);

    @Select("""
            SELECT EXISTS (
                SELECT 1 FROM message_delivery_outbox
                WHERE id = #{id} AND state = 'SENDING' AND claim_token = #{claimToken}
                  AND lease_until > UTC_TIMESTAMP(6)
            )
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    boolean owns(@Param("id") long id, @Param("claimToken") String claimToken);

    @Update("""
            UPDATE message_delivery_outbox
            SET state = #{targetState}, completed_at = UTC_TIMESTAMP(6),
                claim_token = NULL, lease_until = NULL, last_error = #{errorCode}
            WHERE id = #{id} AND state = 'SENDING' AND claim_token = #{claimToken}
              AND lease_until > UTC_TIMESTAMP(6)
            """)
    int complete(@Param("id") long id, @Param("claimToken") String claimToken,
                 @Param("targetState") String targetState, @Param("errorCode") String errorCode);

    @Update("""
            UPDATE message_delivery_outbox
            SET state = 'PENDING', claim_token = NULL, lease_until = NULL,
                last_error = #{errorCode},
                available_at = TIMESTAMPADD(MICROSECOND,
                    GREATEST(CASE WHEN #{errorCode} = 'APNS_MORE_TARGETS' THEN 0
                             ELSE LEAST(300, 1 << LEAST(GREATEST(attempts - 1, 0), 9)) * 1000000 END,
                             #{minimumDelayMicros}), UTC_TIMESTAMP(6))
            WHERE id = #{id} AND state = 'SENDING' AND claim_token = #{claimToken}
              AND lease_until > UTC_TIMESTAMP(6)
            """)
    int retry(@Param("id") long id, @Param("claimToken") String claimToken,
              @Param("errorCode") String errorCode, @Param("minimumDelayMicros") long minimumDelayMicros);

    @Select("""
            SELECT COALESCE(SUM(state = 'PENDING'), 0) AS pending,
                   COALESCE(SUM(state = 'SENDING'), 0) AS sending,
                   COALESCE(SUM(state = 'FAILED'), 0) AS failed,
                   GREATEST(0, COALESCE(TIMESTAMPDIFF(SECOND,
                       MIN(CASE WHEN state IN ('PENDING', 'SENDING') THEN created_at ELSE NULL END),
                       UTC_TIMESTAMP(6)), 0)) AS oldest_age
            FROM message_delivery_outbox
            WHERE state IN ('PENDING', 'SENDING', 'FAILED')
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @ConstructorArgs({
            @Arg(column = "pending", javaType = long.class),
            @Arg(column = "sending", javaType = long.class),
            @Arg(column = "failed", javaType = long.class),
            @Arg(column = "oldest_age", javaType = long.class)
    })
    MessageOutboxStore.Summary summary();

    record ReadyDelivery(long id, String eventId, long messageId, String channel, int attempts) {}
}
