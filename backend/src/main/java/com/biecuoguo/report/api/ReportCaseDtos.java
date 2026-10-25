package com.biecuoguo.report.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

/** 举报处理单（O01）与举报人回执（O03）。 */
public final class ReportCaseDtos {
    private ReportCaseDtos() {
    }

    public record CategoryCount(String category, String label, long count) {}

    /**
     * 审核员看到的处理单。targetStatus 是被举报对象此刻的状态（帖子 published / pending / removed / deleted，
     * 评论 visible / hidden，用户 active / disabled / deleted），方便判断是否已被别处处理。
     */
    public record CaseView(long id, String targetType, long targetId, String targetStatus,
                           String targetUserUid, String targetUserNickname, String status, int reportCount,
                           List<CategoryCount> categories, String snapshot,
                           OffsetDateTime firstReportedAt, OffsetDateTime lastReportedAt,
                           String assigneeUid, String assigneeNickname, OffsetDateTime claimedAt,
                           String resolution, String resolutionNote, OffsetDateTime handledAt, int version) {}

    public record CasePage(List<CaseView> items, Long nextBeforeId) {}

    /** 单条举报：source 为 USER（用户举报）或 FILTER（内容过滤自动标记，没有举报人）。 */
    public record CaseReport(long id, String source, String category, String categoryLabel, String detail,
                             String reporterUid, OffsetDateTime createdAt) {}

    public record CaseDetail(CaseView kase, List<CaseReport> reports) {}

    public record ClaimRequest(@NotNull Integer version) {}

    /**
     * action：REMOVE_CONTENT（下架帖子或隐藏评论）、APPROVE_CONTENT（待审核帖子通过）、
     * NO_VIOLATION（未违规，驳回）、HANDLED（已用其他方式处理，如线下提醒）。理由必填，进审计。
     */
    public record ResolveRequest(@NotNull Integer version, @NotBlank String action,
                                 @NotBlank @Size(max = 500) String reason) {}

    /** 举报人自己的举报记录：只给结果，不给处理人、理由和其他人的举报。 */
    public record MyReport(long id, String targetType, String excerpt, String category, String categoryLabel,
                           String status, String result, OffsetDateTime createdAt, OffsetDateTime handledAt) {}

    public record MyReportPage(List<MyReport> items, Long nextBeforeId) {}
}
