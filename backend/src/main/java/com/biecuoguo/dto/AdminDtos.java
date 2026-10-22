package com.biecuoguo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

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

    public record UserStatusRequest(@NotBlank @Pattern(regexp = "active|disabled") String status) {}
    public record UserRoleRequest(@NotBlank @Pattern(regexp = "USER|OPERATOR|SCHOOL_OPERATOR|COLLEGE_OPERATOR|MAJOR_OPERATOR|ADMIN") String role,
                                  @Size(max = 32) String operatorScope) {}
}
