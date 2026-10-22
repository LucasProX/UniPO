package com.biecuoguo.dto;

import com.biecuoguo.domain.User;
import com.biecuoguo.domain.UserPreference;

import com.biecuoguo.config.AppTime;

import java.time.OffsetDateTime;

public record UserProfile(
        Long id,
        String publicUid,
        String email,
        String nickname,
        String avatarUrl,
        Long schoolId,
        Long collegeId,
        Long majorId,
        String college,
        String major,
        String grade,
        String bio,
        String verifiedStatus,
        String role,
        String operatorScope,
        Integer level,
        Integer xp,
        String levelTitle,
        String status,
        Boolean online,
        String goalTags,
        String interestCategories,
        Boolean preferUrgent,
        Boolean preferHighRisk,
        Boolean preferSameMajor,
        Boolean emailNotificationEnabled,
        Boolean browserNotificationEnabled,
        Integer remindBeforeHours,
        OffsetDateTime createdAt,
        OffsetDateTime lastLoginAt,
        String gender,
        Integer birthYear,
        String zodiac,
        String mbti,
        Boolean showGender,
        Boolean showAge,
        Boolean showGrade,
        Boolean onboardingCompleted,
        String backgroundUrl,
        /** 入学年份；他人可见性跟随年级展示开关。 */
        Integer entryYear
) {
    public static UserProfile from(User user, UserPreference preference) {
        return from(user, preference, false);
    }

    public static UserProfile from(User user, UserPreference preference, boolean online) {
        return view(user, preference, online, true);
    }

    /** 公共主页只返回展示所需资料，不泄露登录账号、通知偏好或关闭展示的个人信息。 */
    public static UserProfile publicView(User user, boolean online) {
        return view(user, null, online, false);
    }

    private static UserProfile view(User user, UserPreference preference, boolean online, boolean owner) {
        return new UserProfile(
                user.getId(),
                user.getPublicUid(),
                owner ? user.getEmail() : null,
                user.getNickname(),
                user.getAvatarUrl(),
                user.getSchoolId(),
                user.getCollegeId(),
                user.getMajorId(),
                user.getCollege(),
                user.getMajor(),
                owner || Boolean.TRUE.equals(user.getShowGrade()) ? user.getGrade() : null,
                user.getBio(),
                user.getVerifiedStatus(),
                user.getRole(),
                user.getOperatorScope(),
                user.getLevel() == null ? 1 : user.getLevel(),
                user.getXp() == null ? 0 : user.getXp(),
                user.getLevelTitle() == null ? "萌新探路员" : user.getLevelTitle(),
                user.getStatus(),
                online,
                owner ? (preference == null ? "" : preference.getGoalTags()) : null,
                owner ? (preference == null ? "" : preference.getInterestCategories()) : null,
                owner ? preference != null && Boolean.TRUE.equals(preference.getPreferUrgent()) : null,
                owner ? preference != null && Boolean.TRUE.equals(preference.getPreferHighRisk()) : null,
                owner ? preference != null && Boolean.TRUE.equals(preference.getPreferSameMajor()) : null,
                owner ? preference != null && Boolean.TRUE.equals(preference.getEmailNotificationEnabled()) : null,
                owner ? preference != null && Boolean.TRUE.equals(preference.getBrowserNotificationEnabled()) : null,
                owner ? (preference == null ? 24 : preference.getRemindBeforeHours()) : null,
                AppTime.toOffset(user.getCreatedAt()),
                owner ? AppTime.toOffset(user.getLastLoginAt()) : null,
                owner || Boolean.TRUE.equals(user.getShowGender()) ? user.getGender() : null,
                owner || Boolean.TRUE.equals(user.getShowAge()) ? user.getBirthYear() : null,
                user.getZodiac(),
                user.getMbti(),
                Boolean.TRUE.equals(user.getShowGender()),
                Boolean.TRUE.equals(user.getShowAge()),
                Boolean.TRUE.equals(user.getShowGrade()),
                owner ? Boolean.TRUE.equals(user.getOnboardingCompleted()) : null,
                user.getBackgroundUrl(),
                owner || Boolean.TRUE.equals(user.getShowGrade()) ? user.getEntryYear() : null
        );
    }
}
