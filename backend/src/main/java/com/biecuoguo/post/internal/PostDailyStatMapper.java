package com.biecuoguo.post.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDate;

public interface PostDailyStatMapper extends BaseMapper<PostDailyStat> {
    @Insert("""
            INSERT INTO post_daily_stats(post_id, stat_date, like_count, comment_count, share_count)
            VALUES(#{postId}, #{date}, #{likes}, #{comments}, #{shares})
            ON DUPLICATE KEY UPDATE like_count = post_daily_stats.like_count + #{likes},
              comment_count = post_daily_stats.comment_count + #{comments},
              share_count = post_daily_stats.share_count + #{shares}
            """)
    int increment(@Param("postId") long postId, @Param("date") LocalDate date, @Param("likes") int likes,
                  @Param("comments") int comments, @Param("shares") int shares);
}
