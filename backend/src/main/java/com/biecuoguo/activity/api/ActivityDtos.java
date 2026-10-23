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
            Integer rewardCoins,
            Boolean requireApproval
    ) {}

    /** 编辑只改给出的字段；活动开始后不能编辑。名额不能低于已报名人数，扩容时自动递补候补。 */
    public record UpdateActivityRequest(
            @Size(max = 80) String title,
            @Size(max = 80) String organizerName,
            @Size(max = 500) String coverUrl,
            @Size(max = 5000) String description,
            @Size(max = 1000) String notice,
            @Size(max = 120) String location,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            OffsetDateTime signupDeadline,
            Integer capacity,
            Boolean clearCapacity,
            Boolean waitlistEnabled,
            Integer rewardXp,
            Integer rewardCoins
    ) {}

    public record ReasonRequest(@NotBlank @Size(max = 200) String reason) {}

    /** 名单里的一行：只给组织者看，不含手机号等账号信息。 */
    public record RosterEntry(long registrationId, String uid, String nickname, String avatarUrl,
                              String college, String major, String grade, String status,
                              OffsetDateTime queuedAt, OffsetDateTime checkedInAt, String checkinMethod,
                              int rewardXpGranted, int rewardCoinsGranted, OffsetDateTime checkinRevokedAt,
                              String revokeReason, boolean revocable) {}

    public record RosterPage(List<RosterEntry> items, String nextCursor, boolean hasMore,
                             int registeredCount, int waitlistCount, int pendingCount, int checkedInCount) {}

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
            OffsetDateTime createdAt,
            boolean checkedIn,
            OffsetDateTime checkedInAt,
            /** 签到时间窗：开始前 30 分钟到结束。 */
            boolean checkinOpen,
            /** 当前账号是发布者或管理员，可以打开签到码页面。 */
            boolean canManage,
            boolean requireApproval,
            String cancelReason,
            /** 组织者撤销了我的签到；撤销后不能再次签到。 */
            boolean checkinRevoked
    ) {}

    /** token 来自扫码（动态二维码），code 是现场公布的 4 位签到码；两者给一个即可。 */
    public record CheckinRequest(@Size(max = 64) String token, @Size(max = 8) String code) {}

    public record CheckinResult(ActivityView activity, boolean alreadyCheckedIn, int rewardXp, int rewardCoins) {}

    /** 组织者签到页：二维码内容约 30 秒轮换一次，客户端按 refreshAfterSeconds 重新拉取。 */
    public record OrganizerCheckinView(String qrPayload, String code, int refreshAfterSeconds, boolean windowOpen,
                                       OffsetDateTime windowOpensAt, OffsetDateTime windowClosesAt,
                                       int checkedInCount, int registeredCount) {}

    public record ActivityPage(List<ActivityView> items, String nextCursor, boolean hasMore, int limit) {}

    /** 发布表单用：当前账号能否发布、能面向哪些范围、奖励上限。 */
    public record OrganizerProfile(boolean canPublish, String level, List<String> allowedScopes,
                                   Long collegeId, String collegeName, Long majorId, String majorName,
                                   int maxRewardXp, int maxRewardCoins) {}
}
