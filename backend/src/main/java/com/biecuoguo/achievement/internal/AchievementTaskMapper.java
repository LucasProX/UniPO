package com.biecuoguo.achievement.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AchievementTaskMapper extends BaseMapper<AchievementTask> {
    /** 改攻略关联前锁任务行，同一任务的关联变更串行。 */
    @Select("SELECT * FROM achievement_tasks WHERE id = #{id} FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    AchievementTask lockById(@Param("id") long id);
}
