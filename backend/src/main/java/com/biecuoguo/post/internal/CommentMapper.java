package com.biecuoguo.post.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.time.LocalDateTime;

public interface CommentMapper extends BaseMapper<Comment> {
    @Select("SELECT * FROM comments WHERE id = #{id} FOR UPDATE")
    Comment lockById(@Param("id") long id);

    @Update("UPDATE comments SET like_count = GREATEST(0, like_count + #{delta}), updated_at = #{now} WHERE id = #{id}")
    int adjustLikes(@Param("id") long id, @Param("delta") int delta, @Param("now") LocalDateTime now);

    @Update("UPDATE comments SET reply_count = reply_count + 1, updated_at = #{now} WHERE id = #{id}")
    int incrementReplies(@Param("id") long id, @Param("now") LocalDateTime now);

    @Update("UPDATE comments SET reply_count = GREATEST(0, reply_count - 1), updated_at = #{now} WHERE id = #{id}")
    int decrementReplies(@Param("id") long id, @Param("now") LocalDateTime now);
}
