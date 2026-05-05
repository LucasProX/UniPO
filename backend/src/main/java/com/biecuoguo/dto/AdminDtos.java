package com.biecuoguo.dto;

public final class AdminDtos {
    private AdminDtos() {}

    public record AnalyticsOverview(
            long users,
            long notices,
            long publishedNotices,
            long comments,
            long favorites,
            long reminders
    ) {}

    public record UserStatusRequest(String status) {}
    public record UserRoleRequest(String role, String operatorScope) {}
}
