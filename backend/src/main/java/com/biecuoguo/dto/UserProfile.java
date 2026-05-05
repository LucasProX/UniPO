package com.biecuoguo.dto;

import com.biecuoguo.domain.User;
import com.biecuoguo.domain.UserPreference;

import java.time.LocalDateTime;

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
        LocalDateTime createdAt,
        LocalDateTime lastLoginAt
) {
    public static UserProfile from(User user, UserPreference preference) {
        return from(user, preference, false);
    }

    public static UserProfile from(User user, UserPreference preference, boolean online) {
        return new UserProfile(
                user.getId(),
                user.getPublicUid(),
                user.getEmail(),
                user.getNickname(),
                user.getAvatarUrl(),
                user.getSchoolId(),
                user.getCollegeId(),
                user.getMajorId(),
                user.getCollege(),
                user.getMajor(),
                user.getGrade(),
                user.getBio(),
                user.getVerifiedStatus(),
                user.getRole(),
                user.getOperatorScope(),
                user.getLevel() == null ? 1 : user.getLevel(),
                user.getXp() == null ? 0 : user.getXp(),
                user.getLevelTitle() == null ? "萌新探路员" : user.getLevelTitle(),
                user.getStatus(),
                online,
                preference == null ? "" : preference.getGoalTags(),
                preference == null ? "" : preference.getInterestCategories(),
                preference != null && Boolean.TRUE.equals(preference.getPreferUrgent()),
                preference != null && Boolean.TRUE.equals(preference.getPreferHighRisk()),
                preference != null && Boolean.TRUE.equals(preference.getPreferSameMajor()),
                preference != null && Boolean.TRUE.equals(preference.getEmailNotificationEnabled()),
                preference != null && Boolean.TRUE.equals(preference.getBrowserNotificationEnabled()),
                preference == null ? 24 : preference.getRemindBeforeHours(),
                user.getCreatedAt(),
                user.getLastLoginAt()
        );
    }
}
