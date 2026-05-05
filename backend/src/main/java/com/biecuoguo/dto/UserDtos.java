package com.biecuoguo.dto;

import jakarta.validation.constraints.Size;

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

    public record UserStats(long favorites, long likedPosts, long sharedPosts, long completed, long comments, long upcoming, long avoidedRisks, long following, long followers, long posts) {}

    public record PublicProfile(UserProfile profile, UserStats stats, boolean following, boolean mine) {}
}
