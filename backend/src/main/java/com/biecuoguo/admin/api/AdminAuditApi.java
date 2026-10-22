package com.biecuoguo.admin.api;

import com.biecuoguo.security.CurrentUser;

import java.util.List;

/**
 * 审计记录的跨模块入口。
 *
 * <p>为什么放 api：封号、改角色、下架帖子、改轮播位分散在不同模块的 Controller 里，
 * 它们只能依赖 admin.api，不能依赖 admin.internal（ADR-0002）。
 */
public interface AdminAuditApi {

    void record(CurrentUser actor, String action, String targetType, Object targetId, String detail);

    /** Required audit shares the caller's transaction and propagates failures instead of losing evidence. */
    void recordRequired(CurrentUser actor, String action, String targetType, Object targetId, String detail);

    List<AuditView> recent(int limit);

    record AuditView(Long id, Long actorId, String actorEmail, String action,
                     String targetType, String targetId, String detail,
                     String requestId, String createdAt) {
    }
}
