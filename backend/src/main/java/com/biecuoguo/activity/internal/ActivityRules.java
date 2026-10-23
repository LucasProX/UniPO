package com.biecuoguo.activity.internal;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/** 活动的纯规则：发布权限、面向范围、奖励上限与阶段计算。不碰数据库，便于单测。 */
final class ActivityRules {
    private ActivityRules() {}

    static final Set<String> CATEGORIES = Set.of("LECTURE", "COMPETITION", "VOLUNTEER", "SPORTS", "CULTURE", "CLASS", "OTHER");
    static final Set<String> SCOPES = Set.of("SCHOOL", "COLLEGE", "MAJOR");
    /** 通知正文里的时间：不带秒，和 App 内展示一致。 */
    static final java.time.format.DateTimeFormatter DAY_TIME = java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm");
    static final java.time.format.DateTimeFormatter TIME = java.time.format.DateTimeFormatter.ofPattern("HH:mm");

    enum Level {
        ADMIN(100, 200), SCHOOL(100, 200), COLLEGE(50, 100), MAJOR(30, 60);

        final int maxXp;
        final int maxCoins;

        Level(int maxXp, int maxCoins) {
            this.maxXp = maxXp;
            this.maxCoins = maxCoins;
        }

        List<String> allowedScopes() {
            return switch (this) {
                case ADMIN, SCHOOL -> List.of("SCHOOL", "COLLEGE", "MAJOR");
                case COLLEGE -> List.of("COLLEGE", "MAJOR");
                case MAJOR -> List.of("MAJOR");
            };
        }
    }

    /** 普通用户与没有运营范围的账号返回 null：不能发布带奖励的官方活动。 */
    static Level levelOf(String role, String operatorScope) {
        if ("ADMIN".equals(role)) return Level.ADMIN;
        if (role == null || "USER".equals(role)) return null;
        if (operatorScope == null) return null;
        return switch (operatorScope) {
            case "school" -> Level.SCHOOL;
            case "college" -> Level.COLLEGE;
            case "major" -> Level.MAJOR;
            default -> null;
        };
    }

    /**
     * 组织者只能面向自己权限内的范围：院级只能本院（含本院专业），专业运营只能本专业。
     * majorCollegeId 是目标专业所属学院，用于院级运营发布到本院某个专业。
     */
    static void checkAudience(Level level, Long organizerCollegeId, Long organizerMajorId,
                              String scope, Long collegeId, Long majorId, Long majorCollegeId) {
        if (!level.allowedScopes().contains(scope)) {
            throw new IllegalArgumentException("当前身份不能发布这个范围的活动");
        }
        switch (scope) {
            case "COLLEGE" -> {
                if (collegeId == null) throw new IllegalArgumentException("请选择面向的学院");
                if (level == Level.COLLEGE && !collegeId.equals(organizerCollegeId)) {
                    throw new IllegalArgumentException("只能面向本院发布活动");
                }
            }
            case "MAJOR" -> {
                if (majorId == null) throw new IllegalArgumentException("请选择面向的专业");
                if (level == Level.MAJOR && !majorId.equals(organizerMajorId)) {
                    throw new IllegalArgumentException("只能面向本专业发布活动");
                }
                if (level == Level.COLLEGE && (majorCollegeId == null || !majorCollegeId.equals(organizerCollegeId))) {
                    throw new IllegalArgumentException("只能面向本院的专业发布活动");
                }
            }
            default -> { }
        }
    }

    static void checkReward(Level level, int xp, int coins) {
        if (xp < 0 || coins < 0) throw new IllegalArgumentException("奖励不能为负数");
        if (xp > level.maxXp || coins > level.maxCoins) {
            throw new IllegalArgumentException("奖励超过当前身份上限：最多 " + level.maxXp + " 经验、" + level.maxCoins + " 金币");
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
