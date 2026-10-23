package com.biecuoguo.notification.internal;

import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface NotificationMapper {
    @Insert("""
            INSERT IGNORE INTO user_notifications (user_id, category, type, title, body, activity_id, dedupe_key, created_at)
            VALUES (#{userId}, 'ACTIVITY', #{type}, #{title}, #{body}, #{activityId}, #{dedupeKey}, #{now})
            """)
    int insert(@Param("userId") long userId, @Param("type") String type, @Param("title") String title,
               @Param("body") String body, @Param("activityId") Long activityId, @Param("dedupeKey") String dedupeKey,
               @Param("now") LocalDateTime now);

    @Select("""
            <script>
            SELECT id, category, type, title, body, activity_id, read_at, created_at FROM user_notifications
            WHERE user_id = #{userId}
              <if test="beforeId != null">AND id &lt; #{beforeId}</if>
            ORDER BY id DESC LIMIT #{limit}
            </script>
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @ConstructorArgs({
            @Arg(column = "id", javaType = Long.class),
            @Arg(column = "category", javaType = String.class),
            @Arg(column = "type", javaType = String.class),
            @Arg(column = "title", javaType = String.class),
            @Arg(column = "body", javaType = String.class),
            @Arg(column = "activity_id", javaType = Long.class),
            @Arg(column = "read_at", javaType = LocalDateTime.class),
            @Arg(column = "created_at", javaType = LocalDateTime.class)
    })
    List<Row> page(@Param("userId") long userId, @Param("beforeId") Long beforeId, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM user_notifications WHERE user_id = #{userId} AND read_at IS NULL")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    int unread(@Param("userId") long userId);

    @Update("UPDATE user_notifications SET read_at = #{now} WHERE user_id = #{userId} AND read_at IS NULL")
    int readAll(@Param("userId") long userId, @Param("now") LocalDateTime now);

    record Row(Long id, String category, String type, String title, String body, Long activityId,
               LocalDateTime readAt, LocalDateTime createdAt) {}
}
