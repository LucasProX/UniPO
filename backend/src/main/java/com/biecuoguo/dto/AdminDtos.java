package com.biecuoguo.dto;

public final class AdminDtos {
    private AdminDtos() {}

    /**
     * 管理端概览。notices 域删除后改成按 posts 口径统计：
     * 原来的 notices / publishedNotices / favorites / reminders 换成 posts / publishedPosts。
     */
    public record AnalyticsOverview(
            long users,
            long posts,
            long publishedPosts,
            long comments
    ) {}

    public record UserStatusRequest(String status) {}
    public record UserRoleRequest(String role, String operatorScope) {}
}