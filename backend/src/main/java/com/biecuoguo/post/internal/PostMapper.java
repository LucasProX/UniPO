package com.biecuoguo.post.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.time.LocalDateTime;

public interface PostMapper extends BaseMapper<Post> {
    @Select("SELECT * FROM posts WHERE id = #{id} FOR UPDATE")
    Post lockById(@Param("id") long id);

    @Update("""
            UPDATE posts SET like_count = GREATEST(0, like_count + #{likes}),
              comment_count = GREATEST(0, comment_count + #{comments}),
              favorite_count = GREATEST(0, favorite_count + #{favorites}),
              share_count = GREATEST(0, share_count + #{shares}), updated_at = #{now}
            WHERE id = #{id}
            """)
    int adjustCounters(@Param("id") long id, @Param("likes") int likes, @Param("comments") int comments,
                       @Param("favorites") int favorites, @Param("shares") int shares, @Param("now") LocalDateTime now);
}
