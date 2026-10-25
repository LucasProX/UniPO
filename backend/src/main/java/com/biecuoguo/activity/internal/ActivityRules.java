package com.biecuoguo.activity.internal;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/** 活动的纯规则：奖励校验、阶段与报名资格计算。发布权限与面向范围在 permission 模块（ADR-0006）。不碰数据库，便于单测。 */
final class ActivityRules {
    private ActivityRules() {}

    static final Set<String> CATEGORIES = Set.of("LECTURE", "COMPETITION", "VOLUNTEER", "SPORTS", "CULTURE", "CLASS", "OTHER");
    static final Set<String> SCOPES = Set.of("SCHOOL", "COLLEGE", "MAJOR");
    /** 通知正文里的时间：不带秒，和 App 内展示一致。 */
    static final java.time.format.DateTimeFormatter DAY_TIME = java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm");
    static final java.time.format.DateTimeFormatter TIME = java.time.format.DateTimeFormatter.ofPattern("HH:mm");

    /** 上限来自发布者的授权（{@link com.biecuoguo.permission.api.Role#rewardCap()}），超出直接拒绝，不静默截断。 */
    static void checkReward(com.biecuoguo.permission.api.RewardCap cap, int xp, int coins) {
        if (xp < 0 || coins < 0) throw new IllegalArgumentException("奖励不能为负数");
        if (xp > cap.maxXp() || coins > cap.maxCoins()) {
            throw new IllegalArgumentException("奖励超过当前身份上限：最多 " + cap.maxXp() + " 经验、" + cap.maxCoins() + " 金币");
        }
    }

    static String phase(String status, LocalDateTime signupDeadline, LocalDateTime startsAt, LocalDateTime endsAt,
                        LocalDateTime now) {
        if ("CANCELLED".equals(status)) return "CANCELLED";
        if (!now.isBefore(endsAt)) return "ENDED";
        if (!now.isBefore(startsAt)) return "ONGOING";
        if (!now.isBefore(signupDeadline)) return "CLOSED";
        return "OPEN";
    }

    /** 返回 null 表示可以报名；否则是给人看的原因。 */
    static String ineligibleReason(Activity activity, Long schoolId, Long collegeId, Long majorId, Integer entryYear,
                                   String audienceLabel) {
        if (schoolId == null || !schoolId.equals(activity.getSchoolId())) return "仅限本校同学报名";
        boolean inScope = switch (activity.getAudienceScope()) {
            case "COLLEGE" -> activity.getAudienceCollegeId() != null && activity.getAudienceCollegeId().equals(collegeId);
            case "MAJOR" -> activity.getAudienceMajorId() != null && activity.getAudienceMajorId().equals(majorId);
            default -> true;
        };
        if (!inScope) return "仅限" + audienceLabel + "报名，可在「成长」里补充学院和专业";
        if (activity.getAudienceEntryYear() != null && !activity.getAudienceEntryYear().equals(entryYear)) {
            return "仅限 " + activity.getAudienceEntryYear() + " 级同学报名";
        }
        return null;
    }
}
