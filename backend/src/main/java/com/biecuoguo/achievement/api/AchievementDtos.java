package com.biecuoguo.achievement.api;

import com.biecuoguo.post.api.PostDtos;

import java.util.List;

public final class AchievementDtos {
    private AchievementDtos() {}

    public record TaskView(
            Long id,
            String title,
            String description,
            int xp,
            boolean completed,
            String completedAt,
            /** 期限提示，例如「退改选截止前」；具体日期以学校通知为准。 */
            String dueHint
    ) {}

    /** 任务详情（G05）：步骤、期限、来源与复核日期、内容版本。 */
    public record TaskDetailView(
            Long id,
            String stageCode,
            String stageTitle,
            String title,
            String description,
            int xp,
            List<String> steps,
            String dueHint,
            /** PLATFORM 平台整理 / SCHOOL 学校官方 / COMMUNITY 同学经验 */
            String sourceType,
            String sourceName,
            String sourceUrl,
            /** 最近一次复核日期，yyyy-MM-dd。 */
            String reviewedAt,
            int version,
            boolean completed,
            String completedAt,
            /** 完成时的内容版本；未完成为空。 */
            Integer completedVersion,
            /** 完成之后任务内容又更新过。完成事实保留，只做提示。 */
            boolean updatedSinceCompletion,
            /** 完成会得到的徽章；没有为空。 */
            BadgeView badge,
            /** 同校、已发布、未过期的关联攻略，按运营排序。 */
            List<PostDtos.PostView> guides,
            /** 关联了但已过有效期、因此不再展示的攻略数。 */
            int expiredGuideCount
    ) {}

    /** 后台：任务的全部关联攻略，包括下架、过期和别校的。 */
    public record GuideLinkView(
            Long postId,
            int sortOrder,
            String addedAt,
            /** 帖子已被删除时为空。 */
            PostDtos.PostView post
    ) {}

    public record GuideLinkRequest(
            @jakarta.validation.constraints.NotNull Long postId,
            Integer sortOrder
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
            TaskView nextTask,
            /** 主页称号：选中且仍然有效的那枚徽章；没选或已失效为空（F15）。 */
            BadgeView title
    ) {}

    /** 选称号：badgeCode 为空表示不展示称号。只能选已获得的徽章。 */
    public record TitleRequest(String badgeCode) {}
}
