package com.biecuoguo.achievement.api;

import java.util.Collection;
import java.util.Map;

/** 帖子挂主线任务时用的轻量查询；单独成 bean，避免和依赖帖子模块的 AchievementService 形成循环。 */
public interface TaskLookupApi {
    /** 任务 ID → 标题；不存在的任务不出现在结果里。 */
    Map<Long, String> titles(Collection<Long> taskIds);
}
