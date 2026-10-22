package com.biecuoguo.message.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.ResultMap;

import java.util.List;

public interface MessageMapper extends BaseMapper<Message> {

    /** The covering conversation/id index supplies one current maximum, not every historical body. */
    @Select("""
            <script>
            SELECT m.* FROM messages m
            JOIN (
              SELECT conversation_id, MAX(id) AS message_id FROM messages
              WHERE conversation_id IN
              <foreach collection="conversationIds" item="conversationId" open="(" separator="," close=")">
                #{conversationId}
              </foreach>
              GROUP BY conversation_id
            ) latest ON latest.message_id = m.id
            </script>
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Results(id = "messageRow", value = {
            @Result(column = "id", property = "id", id = true),
            @Result(column = "conversation_id", property = "conversationId"),
            @Result(column = "sender_id", property = "senderId"),
            @Result(column = "receiver_id", property = "receiverId"),
            @Result(column = "content", property = "content"),
            @Result(column = "client_message_id", property = "clientMessageId"),
            @Result(column = "is_read", property = "readFlag"),
            @Result(column = "created_at", property = "createdAt")
    })
    List<Message> selectLatestForConversations(@Param("conversationIds") List<Long> conversationIds);

    @Select("""
            <script>
            SELECT * FROM messages WHERE conversation_id = #{conversationId}
              <if test="beforeId != null">AND id &lt; #{beforeId}</if>
            ORDER BY id DESC LIMIT #{limit}
            </script>
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @ResultMap("messageRow")
    List<Message> selectPageRows(@Param("conversationId") long conversationId,
                                 @Param("beforeId") Long beforeId, @Param("limit") int limit);

    @Select("SELECT MAX(id) FROM messages WHERE conversation_id = #{conversationId}")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Long selectLatestMessageId(@Param("conversationId") long conversationId);

    @Select("""
            SELECT * FROM messages WHERE conversation_id = #{conversationId}
              AND id > #{afterId} AND id <= #{throughId}
            ORDER BY id ASC LIMIT #{limit}
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @ResultMap("messageRow")
    List<Message> selectNewerPageRows(@Param("conversationId") long conversationId,
                                      @Param("afterId") long afterId, @Param("throughId") long throughId,
                                      @Param("limit") int limit);

    /** An absent optimistic lookup must not take an RR gap lock before the insert. */
    @Select("""
            SELECT * FROM messages
            WHERE sender_id = #{senderId} AND client_message_id = #{clientMessageId}
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @ResultMap("messageRow")
    Message selectClientIntent(@Param("senderId") long senderId, @Param("clientMessageId") String clientMessageId);

    /** Recheck a known committed key after an optimistic hit or a unique-key wait. */
    @Select("""
            SELECT * FROM messages
            WHERE sender_id = #{senderId} AND client_message_id = #{clientMessageId}
            FOR SHARE
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @ResultMap("messageRow")
    Message selectClientIntentCurrent(@Param("senderId") long senderId,
                                      @Param("clientMessageId") String clientMessageId);

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
