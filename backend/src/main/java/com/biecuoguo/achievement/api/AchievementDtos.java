package com.biecuoguo.achievement.api;

import java.util.List;

public final class AchievementDtos {
    private AchievementDtos() {}

    public record TaskView(
            Long id,
            String title,
            String description,
            int xp,
            boolean completed,
            String completedAt
    ) {}

    public record StageView(
            Long id,
            String code,
            String title,
            String subtitle,
            int order,
            boolean boss,
            List<TaskView> tasks,
            int completedCount,
            int totalCount
    ) {}

    public record BadgeView(
            String code,
            String title,
            String description,
            String rarity,
            String icon,
            boolean earned,
            String earnedAt
    ) {}

    public record AchievementView(
            int level,
            int xp,
            int completedTasks,
            int totalTasks,
            String currentStageCode,
            List<StageView> stages,
            List<BadgeView> badges,
            /** CALENDAR = 按入学年份与日期推算；PROGRESS = 尚无入学年份，取第一个未完成阶段。 */
            String stageBasis,
            Integer entryYear,
            /** 例如「大一上学期」；没有入学年份时为空。 */
            String termLabel,
            /** 当前阶段里第一个未完成的任务；当前阶段已全部完成时为空。 */
            TaskView nextTask
    ) {}
}
