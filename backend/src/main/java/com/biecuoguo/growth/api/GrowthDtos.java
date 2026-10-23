package com.biecuoguo.growth.api;

import com.biecuoguo.achievement.api.AchievementDtos;
import com.biecuoguo.activity.api.ActivityParticipationApi;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class GrowthDtos {
    private GrowthDtos() {}

    /** 个人自定义任务：只给自己看，不发经验和金币。 */
    public record PersonalTaskView(
            long id,
            LocalDate termStart,
            String title,
            String note,
            OffsetDateTime dueAt,
            boolean completed,
            OffsetDateTime completedAt,
            OffsetDateTime createdAt
    ) {}

    public record PersonalTaskRequest(
            @NotBlank @Size(max = 40) String title,
            @Size(max = 200) String note,
            OffsetDateTime dueAt,
            /** 归属学期的起始日；为空时放进当前学期。只能是当前或下一个学期。 */
            LocalDate termStart
    ) {}

    public record TermSummary(
            int done,
            int total,
            int attended,
            int activityXp,
            int activityCoins
    ) {}

    /** 本学期清单：已报名的活动 + 当期主线任务 + 个人任务。签到后活动那一项自动完成。 */
    public record TermPlanView(
            LocalDate termStart,
            /** 学期最后一天（含）。 */
            LocalDate termEnd,
            String termLabel,
            /** 有入学年份时的学期序号（0 = 大一上）；没有时为空。 */
            Integer termIndex,
            boolean current,
            LocalDate previousTermStart,
            /** 只允许往后看一个学期；已经是下一个学期时为空。 */
            LocalDate nextTermStart,
            /** 当前或下一个学期才能新增个人任务。 */
            boolean canAddTasks,
            TermSummary summary,
            List<ActivityParticipationApi.Participation> activities,
            /** 当期主线阶段；没有入学年份且不是当前学期时为空。 */
            AchievementDtos.StageView officialStage,
            List<PersonalTaskView> personalTasks
    ) {}

    public record ArchiveTerm(
            LocalDate termStart,
            String termLabel,
            boolean current,
            List<ActivityParticipationApi.Participation> activities,
            List<AchievementDtos.BadgeView> badges,
            int officialCompleted,
            int personalCompleted,
            int activityXp,
            int activityCoins
    ) {}

    /** 成长档案：按学期倒序归档参加过的活动、获得的徽章和完成数。 */
    public record ArchiveView(
            int attendedTotal,
            int badgeTotal,
            List<ArchiveTerm> terms
    ) {}
}
