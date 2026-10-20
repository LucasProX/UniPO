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
            List<BadgeView> badges
    ) {}
}
