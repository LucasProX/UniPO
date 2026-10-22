package com.biecuoguo.message.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface MessageMapper extends BaseMapper<Message> {

    @Select("""
            <script>
            SELECT conversation_id, COUNT(*) AS unread_count
            FROM messages
            WHERE receiver_id = #{receiverId} AND is_read = false
              AND conversation_id IN
              <foreach collection="conversationIds" item="conversationId" open="(" separator="," close=")">
                #{conversationId}
              </foreach>
            GROUP BY conversation_id
            </script>
            """)
    @ConstructorArgs({
            @Arg(column = "conversation_id", javaType = Long.class),
            @Arg(column = "unread_count", javaType = Long.class)
    })
    List<UnreadCount> selectUnreadCounts(@Param("receiverId") long receiverId,
                                       @Param("conversationIds") List<Long> conversationIds);

    record UnreadCount(Long conversationId, Long unreadCount) {}
}
