package com.biecuoguo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public final class UserDtos {
    private UserDtos() {}

    public record UpdateProfileRequest(
            @Size(max = 40) String nickname,
            String avatarUrl,
            Long schoolId,
            Long collegeId,
            Long majorId,
            @Size(max = 80) String college,
            @Size(max = 80) String major,
            @Size(max = 30) String grade,
            @Size(max = 400) String bio
    ) {}

    /** 3 步新手引导的最终提交；字段都可选，允许用户跳过部分信息。 */
    public record OnboardingRequest(
            @Size(max = 40) String nickname,
            String gender,
            Integer birthYear,
            @Size(max = 30) String grade,
            @Size(max = 10) String zodiac,
            @Size(max = 8) String mbti,
            @Size(max = 300) String signature,
            Boolean showGender,
            Boolean showAge,
            Boolean showGrade,
            @Size(max = 500) String avatarUrl
    ) {}

    public record UpdatePreferencesRequest(
            String goalTags,
            String interestCategories,
            Boolean preferUrgent,
            Boolean preferHighRisk,
            Boolean preferSameMajor,
            Boolean emailNotificationEnabled,
            Boolean browserNotificationEnabled,
            Integer remindBeforeHours
    ) {}

    /**
     * 用户统计。
     *
     * <p><b>notices 域已删除</b>（2026-10-20），下面三个字段从 notices 语义而来，现在恒为 0：
     * <ul>
     *   <li>{@code completed} —— 原「已完成的通知数」</li>
     *   <li>{@code upcoming} —— 原「临近截止的提醒数」</li>
     *   <li>{@code avoidedRisks} —— 原「避坑数」</li>
     * </ul>
     * 保留它们是因为 {@code /api/v1} 只做加法、不做删除；Web/App 端仍在读这几个键。
     * 等升 v2 时一起去掉。**不要**再往里塞 notices 时代的逻辑。
     *
     * <p>{@code favorites} 现在只统计 **帖子收藏**（原来是「通知收藏 + 帖子收藏」相加）。
     */
    public record UserStats(long favorites, long likedPosts, long sharedPosts, long completed, long comments,
                            long upcoming, long avoidedRisks, long following, long followers, long posts) {}

    public record CheckInView(UserProfile profile, boolean checkedInToday, int streak, int xpGained, LocalDate checkinDate) {}

    /**
     * 关注/粉丝列表里的一行。刻意不返回完整 UserProfile：
     * 列表只需要展示信息，别把 email 等字段带出去。
     */
    public record FollowUserView(
            Long id,
            String publicUid,
            String nickname,
            String avatarUrl,
            String role,
            Integer level,
            String levelTitle,
            String college,
            String major,
            String grade,
            boolean following,
            OffsetDateTime followedAt
    ) {}

    public record PublicProfile(UserProfile profile, UserStats stats, boolean following, boolean mine,
                                boolean blocked, boolean blockedBy) {}

    public record NewFollowersView(long count) {}

    /** 举报分类由后端校验，前端只传稳定 key。 */
    public record ReportRequest(@NotBlank @Size(max = 30) String category,
                                @Size(max = 500) String detail) {}

    public record BlockStatusView(boolean blocked, boolean blockedBy) {}
}
