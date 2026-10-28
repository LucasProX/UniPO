package com.biecuoguo.achievement.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

public interface AchievementTitleMapper extends BaseMapper<AchievementTitle> {
    @Insert("""
            INSERT INTO achievement_titles (user_id, badge_code, updated_at) VALUES (#{userId}, #{badgeCode}, #{now})
            ON DUPLICATE KEY UPDATE badge_code = VALUES(badge_code), updated_at = VALUES(updated_at)
            """)
    int upsert(@Param("userId") long userId, @Param("badgeCode") String badgeCode, @Param("now") LocalDateTime now);
}
