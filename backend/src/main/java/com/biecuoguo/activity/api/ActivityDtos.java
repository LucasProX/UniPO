package com.biecuoguo.activity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

public final class ActivityDtos {
    private ActivityDtos() {}

    public record CreateActivityRequest(
            @NotBlank @Size(max = 80) String title,
            @NotBlank @Size(max = 20) String category,
            @Size(max = 80) String organizerName,
            @Size(max = 500) String coverUrl,
            @NotBlank @Size(max = 5000) String description,
            @Size(max = 1000) String notice,
            @NotBlank @Size(max = 120) String location,
            @NotNull OffsetDateTime startsAt,
            @NotNull OffsetDateTime endsAt,
            OffsetDateTime signupDeadline,
            @NotBlank @Size(max = 10) String audienceScope,
            Long audienceCollegeId,
            Long audienceMajorId,
            Integer audienceEntryYear,
            Integer capacity,
            Boolean waitlistEnabled,
            Integer rewardXp,
            Integer rewardCoins
    ) {}

    public record OrganizerView(Long id, String uid, String nickname, String avatarUrl, String role) {}

    public record AttendeeView(String uid, String nickname, String avatarUrl) {}

    /**
     * phase 由服务端按时间与状态计算：OPEN（报名中）、CLOSED（报名截止）、ONGOING、ENDED、CANCELLED。
     * myStatus：NONE / REGISTERED / WAITLISTED / CANCELLED；未登录为 NONE。
     */
    public record ActivityView(
            Long id,
            String title,
            String category,
            String coverUrl,
            String description,
            String notice,
            String location,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            OffsetDateTime signupDeadline,
            String organizerName,
            OrganizerView organizer,
            String audienceScope,
            Long audienceCollegeId,
            Long audienceMajorId,
            Integer audienceEntryYear,
            String audienceLabel,
            Integer capacity,
            int registeredCount,
            int waitlistCount,
            boolean waitlistEnabled,
            int rewardXp,
            int rewardCoins,
            String phase,
            String myStatus,
            Integer myWaitlistPosition,
            boolean eligible,
            String ineligibleReason,
            List<AttendeeView> recentAttendees,
            OffsetDateTime createdAt
    ) {}

    public record ActivityPage(List<ActivityView> items, String nextCursor, boolean hasMore, int limit) {}

    /** 发布表单用：当前账号能否发布、能面向哪些范围、奖励上限。 */
    public record OrganizerProfile(boolean canPublish, String level, List<String> allowedScopes,
                                   Long collegeId, String collegeName, Long majorId, String majorName,
                                   int maxRewardXp, int maxRewardCoins) {}
}
