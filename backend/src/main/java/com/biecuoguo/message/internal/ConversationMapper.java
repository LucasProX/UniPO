package com.biecuoguo.message.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

public interface ConversationMapper extends BaseMapper<Conversation> {
    /** Each participant-side index contributes at most limit rows before the bounded merge. */
    @Select("""
            <script>
            SELECT * FROM (
              (SELECT c.* FROM conversations c
               WHERE c.user_one_id = #{viewerId}
                 <if test="beforeTime != null">
                   AND (c.activity_at &lt; #{beforeTime}
                        OR (c.activity_at = #{beforeTime} AND c.id &lt; #{beforeId}))
                 </if>
                 <if test="!blockedIds.isEmpty()">
                   AND c.user_one_id NOT IN
                   <foreach collection="blockedIds" item="blockedId" open="(" separator="," close=")">#{blockedId}</foreach>
                   AND c.user_two_id NOT IN
                   <foreach collection="blockedIds" item="blockedId" open="(" separator="," close=")">#{blockedId}</foreach>
                 </if>
               ORDER BY c.activity_at DESC, c.id DESC LIMIT #{limit})
              UNION ALL
              (SELECT c.* FROM conversations c
               WHERE c.user_two_id = #{viewerId} AND c.user_one_id != #{viewerId}
                 <if test="beforeTime != null">
                   AND (c.activity_at &lt; #{beforeTime}
                        OR (c.activity_at = #{beforeTime} AND c.id &lt; #{beforeId}))
                 </if>
                 <if test="!blockedIds.isEmpty()">
                   AND c.user_one_id NOT IN
                   <foreach collection="blockedIds" item="blockedId" open="(" separator="," close=")">#{blockedId}</foreach>
                   AND c.user_two_id NOT IN
                   <foreach collection="blockedIds" item="blockedId" open="(" separator="," close=")">#{blockedId}</foreach>
                 </if>
               ORDER BY c.activity_at DESC, c.id DESC LIMIT #{limit})
            ) visible_conversations
            ORDER BY activity_at DESC, id DESC LIMIT #{limit}
            </script>
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<Conversation> selectPageRows(@Param("viewerId") long viewerId, @Param("blockedIds") Set<Long> blockedIds,
                                      @Param("beforeTime") LocalDateTime beforeTime, @Param("beforeId") Long beforeId,
                                      @Param("limit") int limit);
}
