package com.biecuoguo.activity.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

public interface ActivityRegistrationMapper extends BaseMapper<ActivityRegistration> {

    @Select("SELECT * FROM activity_registrations WHERE activity_id = #{activityId} AND user_id = #{userId} FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    ActivityRegistration lockOne(@Param("activityId") long activityId, @Param("userId") long userId);

    /** 候补队首：活动行已被锁住，这里不需要再加锁。 */
    @Select("""
            SELECT * FROM activity_registrations
            WHERE activity_id = #{activityId} AND status = 'WAITLISTED'
            ORDER BY queued_at ASC, id ASC LIMIT 1
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    ActivityRegistration firstWaitlisted(@Param("activityId") long activityId);

    @Select("""
            SELECT COUNT(*) FROM activity_registrations
            WHERE activity_id = #{activityId} AND status = 'WAITLISTED'
              AND (queued_at < #{queuedAt} OR (queued_at = #{queuedAt} AND id <= #{id}))
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    int waitlistPosition(@Param("activityId") long activityId, @Param("queuedAt") LocalDateTime queuedAt,
                         @Param("id") long id);

    /** 我的活动：有效报名对应的活动 ID，按活动开始时间排序（upcoming 正序、history 倒序）。 */
    @Select("""
            <script>
            SELECT a.id FROM activity_registrations r JOIN activities a ON a.id = r.activity_id
            WHERE r.user_id = #{userId} AND r.status IN ('REGISTERED', 'WAITLISTED')
              <choose>
                <when test="history">AND (a.ends_at &lt;= #{now} OR a.status = 'CANCELLED')</when>
                <otherwise>AND a.ends_at &gt; #{now} AND a.status = 'PUBLISHED'</otherwise>
              </choose>
              <if test="afterStart != null">
                <choose>
                  <when test="history">AND (a.starts_at &lt; #{afterStart} OR (a.starts_at = #{afterStart} AND a.id &lt; #{afterId}))</when>
                  <otherwise>AND (a.starts_at &gt; #{afterStart} OR (a.starts_at = #{afterStart} AND a.id &gt; #{afterId}))</otherwise>
                </choose>
              </if>
            ORDER BY
              <choose>
                <when test="history">a.starts_at DESC, a.id DESC</when>
                <otherwise>a.starts_at ASC, a.id ASC</otherwise>
              </choose>
            LIMIT #{limit}
            </script>
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<Long> selectMineIds(@Param("userId") long userId, @Param("history") boolean history,
                             @Param("now") LocalDateTime now, @Param("afterStart") LocalDateTime afterStart,
                             @Param("afterId") Long afterId, @Param("limit") int limit);
}
