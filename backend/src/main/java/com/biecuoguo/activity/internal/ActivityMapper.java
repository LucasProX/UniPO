package com.biecuoguo.activity.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

public interface ActivityMapper extends BaseMapper<Activity> {

    /** 报名、取消和递补都先锁活动行，名额计数因此串行。 */
    @Select("SELECT * FROM activities WHERE id = #{id} FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Activity lockById(@Param("id") long id);

    /** 未结束的活动按开始时间正序；游标是上一页最后一条的 (starts_at, id)。 */
    @Select("""
            <script>
            SELECT * FROM activities
            WHERE school_id = #{schoolId} AND status = 'PUBLISHED' AND ends_at &gt; #{now}
              <if test="startsBefore != null">AND starts_at &lt; #{startsBefore}</if>
              <if test="afterStart != null">
                AND (starts_at &gt; #{afterStart} OR (starts_at = #{afterStart} AND id &gt; #{afterId}))
              </if>
            ORDER BY starts_at ASC, id ASC
            LIMIT #{limit}
            </script>
            """)
    List<Activity> selectUpcoming(@Param("schoolId") long schoolId, @Param("now") LocalDateTime now,
                                  @Param("startsBefore") LocalDateTime startsBefore,
                                  @Param("afterStart") LocalDateTime afterStart, @Param("afterId") Long afterId,
                                  @Param("limit") int limit);
}
