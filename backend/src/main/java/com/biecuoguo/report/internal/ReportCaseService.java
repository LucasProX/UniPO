package com.biecuoguo.report.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.biecuoguo.admin.api.AdminAuditApi;
import com.biecuoguo.config.AppTime;
import com.biecuoguo.domain.User;
import com.biecuoguo.error.StateConflictException;
import com.biecuoguo.mapper.UserMapper;
import com.biecuoguo.notification.api.NotificationApi;
import com.biecuoguo.permission.api.Capability;
import com.biecuoguo.permission.api.Grant;
import com.biecuoguo.permission.api.Grants;
import com.biecuoguo.permission.api.PermissionApi;
import com.biecuoguo.permission.api.ScopeTarget;
import com.biecuoguo.permission.api.ScopeType;
import com.biecuoguo.post.api.PostAdminApi;
import com.biecuoguo.report.api.ReportCaseDtos;
import com.biecuoguo.security.CurrentUser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 举报处理单（O01）：审核员按授权范围查看、领取、处置。每次领取和结案都与必需审计在同一事务里，
 * 并用 version 做乐观校验，两个人同时处置同一单只有一个成功。处置结果通知举报人和内容作者。
 */
@Service
public class ReportCaseService {
    static final int PAGE_SIZE = 30;
    static final Set<String> OPEN = Set.of("PENDING", "IN_PROGRESS");
    private static final Map<String, String> LABELS = Map.of(
            "spam", "垃圾广告", "fraud", "诈骗或虚假信息", "harassment", "骚扰或人身攻击",
            "porn", "色情低俗", "impersonation", "冒充他人", "other", "其他问题");

    private final ReportCaseMapper cases;
    private final ContentReportMapper reports;
    private final UserMapper users;
    private final PostAdminApi posts;
    private final PermissionApi permissions;
    private final AdminAuditApi audit;
    private final NotificationApi notifications;

    public ReportCaseService(ReportCaseMapper cases, ContentReportMapper reports, UserMapper users, PostAdminApi posts,
                             PermissionApi permissions, AdminAuditApi audit, NotificationApi notifications) {
        this.cases = cases;
        this.reports = reports;
        this.users = users;
        this.posts = posts;
        this.permissions = permissions;
        this.audit = audit;
        this.notifications = notifications;
    }

    static String label(String category) {
        if (category == null) return "其他问题";
        if (category.startsWith("filter:")) return "疑似违规内容（系统识别）";
        return LABELS.getOrDefault(category, "其他问题");
    }

    // ---- 查看 ----

    /** status 为空或 open 时看未结案的（待领取 + 处理中），也可传 PENDING / IN_PROGRESS / RESOLVED / REJECTED。 */
    @Transactional(readOnly = true)
    public ReportCaseDtos.CasePage list(CurrentUser actor, String status, Long beforeId) {
        Grants grants = permissions.grantsOf(actor);
        List<Grant> scoped = grants.with(Capability.REPORT_HANDLE);
        boolean unrestricted = grants.platformAdmin() || scoped.stream().anyMatch(g -> g.scopeType() == ScopeType.PLATFORM);
        if (!unrestricted && scoped.isEmpty()) throw new AccessDeniedException("没有权限执行该操作");
        LambdaQueryWrapper<ReportCase> query = new LambdaQueryWrapper<ReportCase>()
                .lt(beforeId != null, ReportCase::getId, beforeId)
                // 与自己有关的举报（自己是被举报人）不出现在自己的队列里，避免既当事人又当裁判。
                .and(w -> w.isNull(ReportCase::getTargetUserId).or().ne(ReportCase::getTargetUserId, actor.id()))
                .orderByDesc(ReportCase::getId)
                .last("LIMIT " + (PAGE_SIZE + 1));
        if (status == null || status.isBlank() || "open".equalsIgnoreCase(status)) query.in(ReportCase::getStatus, OPEN);
        else query.eq(ReportCase::getStatus, status.trim().toUpperCase(Locale.ROOT));
        if (!unrestricted) {
            query.and(w -> {
                for (int i = 0; i < scoped.size(); i++) {
                    Grant grant = scoped.get(i);
                    if (i == 0) w.nested(x -> withinGrant(x, grant)); else w.or(x -> withinGrant(x, grant));
                }
            });
        }
        List<ReportCase> rows = cases.selectList(query);
        boolean more = rows.size() > PAGE_SIZE;
        List<ReportCase> page = more ? rows.subList(0, PAGE_SIZE) : rows;
        return new ReportCaseDtos.CasePage(views(page), more ? page.getLast().getId() : null);
    }

    private static void withinGrant(LambdaQueryWrapper<ReportCase> w, Grant grant) {
        switch (grant.scopeType()) {
            case SCHOOL -> w.eq(ReportCase::getSchoolId, grant.scopeId());
            case COLLEGE -> w.eq(ReportCase::getSchoolId, grant.schoolId()).eq(ReportCase::getCollegeId, grant.scopeId());
            case MAJOR -> w.eq(ReportCase::getSchoolId, grant.schoolId()).eq(ReportCase::getMajorId, grant.scopeId());
            case PLATFORM -> { }
        }
    }

    @Transactional(readOnly = true)
    public ReportCaseDtos.CaseDetail detail(CurrentUser actor, long id) {
        ReportCase kase = requireHandled(actor, id);
        List<ContentReport> rows = reports.selectList(new LambdaQueryWrapper<ContentReport>()
                .eq(ContentReport::getCaseId, id).orderByAsc(ContentReport::getId));
        Map<Long, User> reporters = usersById(rows.stream().map(ContentReport::getReporterId).filter(Objects::nonNull).toList());
        List<ReportCaseDtos.CaseReport> items = rows.stream().map(r -> {
            User reporter = r.getReporterId() == null ? null : reporters.get(r.getReporterId());
            return new ReportCaseDtos.CaseReport(r.getId(), r.getSource(), r.getCategory(), label(r.getCategory()),
                    r.getDetail(), reporter == null ? null : reporter.getPublicUid(), AppTime.toOffset(r.getCreatedAt()));
        }).toList();
        return new ReportCaseDtos.CaseDetail(views(List.of(kase)).getFirst(), items);
    }

    // ---- 处置 ----

    @Transactional
    public ReportCaseDtos.CaseView claim(CurrentUser actor, long id, ReportCaseDtos.ClaimRequest request) {
        ReportCase kase = requireHandled(actor, id);
        if (!OPEN.contains(kase.getStatus())) throw new StateConflictException("这个处理单已经结案");
        if (kase.getAssigneeId() != null && !kase.getAssigneeId().equals(actor.id())) {
            throw new StateConflictException("已由其他审核员领取");
        }
        LocalDateTime now = AppTime.nowLocal().truncatedTo(ChronoUnit.SECONDS);
        int updated = cases.update(null, new LambdaUpdateWrapper<ReportCase>()
                .eq(ReportCase::getId, id).eq(ReportCase::getVersion, request.version())
                .set(ReportCase::getStatus, "IN_PROGRESS").set(ReportCase::getAssigneeId, actor.id())
                .set(ReportCase::getClaimedAt, now).setSql("version = version + 1"));
        if (updated == 0) throw new StateConflictException("处理单已变化，请刷新后重试");
        audit.recordRequired(actor, "REPORT_CLAIM", "report_case", id, "target=" + kase.getTargetType() + ":" + kase.getTargetId());
        return views(List.of(cases.selectById(id))).getFirst();
    }

    @Transactional
    public ReportCaseDtos.CaseView resolve(CurrentUser actor, long id, ReportCaseDtos.ResolveRequest request) {
        ReportCase kase = requireHandled(actor, id);
        if (!OPEN.contains(kase.getStatus())) throw new StateConflictException("这个处理单已经结案");
        if (kase.getAssigneeId() != null && !kase.getAssigneeId().equals(actor.id())
                && !permissions.grantsOf(actor).platformAdmin()) {
            throw new StateConflictException("已由其他审核员领取");
        }
        String action = request.action().trim().toUpperCase(Locale.ROOT);
        String type = kase.getTargetType();
        String resolution;
        switch (action) {
            case "REMOVE_CONTENT" -> {
                if ("POST".equals(type)) {
                    // 作者已经自己删掉或别处已下架的就不再改状态，避免把「已删除」改回「已下架」。
                    String current = posts.targetStatus(type, kase.getTargetId()).orElse("deleted");
                    if (!Set.of("removed", "deleted").contains(current)) {
                        posts.moderate(kase.getTargetId(), null, null, null, "removed", actor);
                    }
                }
                else if ("COMMENT".equals(type)) posts.hideComment(kase.getTargetId(), actor);
                else throw new IllegalArgumentException("举报用户的处理单不能下架内容，请选择其他处理方式");
                resolution = "REMOVED";
            }
            case "APPROVE_CONTENT" -> {
                boolean pendingPost = "POST".equals(type)
                        && "pending".equals(posts.targetStatus(type, kase.getTargetId()).orElse(null));
                if (!pendingPost) throw new IllegalArgumentException("只有待审核的帖子可以通过");
                posts.moderate(kase.getTargetId(), null, null, null, "published", actor);
                resolution = "APPROVED";
            }
            case "NO_VIOLATION" -> resolution = "NO_VIOLATION";
            case "HANDLED" -> resolution = "HANDLED";
            default -> throw new IllegalArgumentException("处理方式不正确");
        }
        String status = "NO_VIOLATION".equals(resolution) ? "REJECTED" : "RESOLVED";
        String reason = request.reason().strip();
        LocalDateTime now = AppTime.nowLocal().truncatedTo(ChronoUnit.SECONDS);
        int updated = cases.update(null, new LambdaUpdateWrapper<ReportCase>()
                .eq(ReportCase::getId, id).eq(ReportCase::getVersion, request.version())
                .set(ReportCase::getStatus, status).set(ReportCase::getResolution, resolution)
                .set(ReportCase::getResolutionNote, reason).set(ReportCase::getHandledBy, actor.id())
                .set(ReportCase::getHandledAt, now).set(ReportCase::getOpenKey, null)
                .setSql("version = version + 1"));
        // 处置内容和结案在同一事务：版本对不上就整体回滚，内容不会被下架两次或状态不一致。
        if (updated == 0) throw new StateConflictException("处理单已变化，请刷新后重试");
        reports.update(null, new LambdaUpdateWrapper<ContentReport>().eq(ContentReport::getCaseId, id)
                .set(ContentReport::getStatus, status).set(ContentReport::getResolution, resolution)
                .set(ContentReport::getHandledBy, actor.id()).set(ContentReport::getHandledAt, now)
                .set(ContentReport::getHandleNote, reason));
        audit.recordRequired(actor, "REPORT_RESOLVE", "report_case", id,
                "target=" + type + ":" + kase.getTargetId() + " action=" + action);
        notifyOutcome(kase, resolution);
        return views(List.of(cases.selectById(id))).getFirst();
    }

    private void notifyOutcome(ReportCase kase, String resolution) {
        List<ContentReport> rows = reports.selectList(new LambdaQueryWrapper<ContentReport>().eq(ContentReport::getCaseId, kase.getId()));
        Set<Long> reporters = rows.stream().map(ContentReport::getReporterId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (kase.getTargetUserId() != null) reporters.remove(kase.getTargetUserId());
        if (!reporters.isEmpty()) {
            notifications.notifySystem(reporters, "REPORT_RESOLVED", "你的举报已处理", reporterMessage(resolution),
                    null, "report-case:" + kase.getId());
        }
        if (kase.getTargetUserId() == null) return;
        String what = "POST".equals(kase.getTargetType()) ? "PO" : "评论";
        if ("REMOVED".equals(resolution)) {
            notifications.notifySystem(List.of(kase.getTargetUserId()), "CONTENT_REMOVED", "你的" + what + "已被下架",
                    "你发布的" + what + "因「" + label(mainCategory(rows)) + "」被下架。如有异议，可以在「设置 → 意见反馈」里说明。",
                    null, "removed:" + kase.getId());
        } else if ("APPROVED".equals(resolution)) {
            notifications.notifySystem(List.of(kase.getTargetUserId()), "POST_APPROVED", "你的 PO 已通过审核",
                    "现在同学们都能在信息流里看到它了。", kase.getTargetId(), "approved:" + kase.getId());
        }
    }

    static String reporterMessage(String resolution) {
        return switch (resolution) {
            case "REMOVED" -> "你举报的内容已被下架，谢谢你帮大家维护社区。";
            case "HANDLED" -> "你举报的问题已经处理，谢谢反馈。";
            default -> "经核实，你举报的内容暂未发现违规。如有新的情况，可以再次举报。";
        };
    }

    static String mainCategory(List<ContentReport> rows) {
        return rows.stream().collect(Collectors.groupingBy(ContentReport::getCategory, LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("other");
    }

    // ---- 内部 ----

    private ReportCase requireHandled(CurrentUser actor, long id) {
        ReportCase kase = cases.selectById(id);
        if (kase == null) throw new NoSuchElementException("处理单不存在");
        permissions.require(actor, Capability.REPORT_HANDLE,
                new ScopeTarget(kase.getSchoolId(), kase.getCollegeId(), kase.getMajorId()));
        if (Objects.equals(kase.getTargetUserId(), actor.id())) {
            throw new StateConflictException("不能处理与自己有关的举报，请交给其他审核员");
        }
        return kase;
    }

    private Map<Long, User> usersById(Collection<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return users.selectBatchIds(new HashSet<>(ids)).stream().collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private List<ReportCaseDtos.CaseView> views(List<ReportCase> rows) {
        if (rows.isEmpty()) return List.of();
        List<Long> ids = rows.stream().map(ReportCase::getId).toList();
        Map<Long, List<ContentReport>> byCase = reports.selectList(new LambdaQueryWrapper<ContentReport>()
                        .in(ContentReport::getCaseId, ids))
                .stream().collect(Collectors.groupingBy(ContentReport::getCaseId));
        Set<Long> userIds = new HashSet<>();
        rows.forEach(r -> {
            if (r.getTargetUserId() != null) userIds.add(r.getTargetUserId());
            if (r.getAssigneeId() != null) userIds.add(r.getAssigneeId());
        });
        Map<Long, User> people = usersById(userIds);
        return rows.stream().map(r -> {
            List<ReportCaseDtos.CategoryCount> categories = byCase.getOrDefault(r.getId(), List.of()).stream()
                    .collect(Collectors.groupingBy(ContentReport::getCategory, TreeMap::new, Collectors.counting()))
                    .entrySet().stream()
                    .map(e -> new ReportCaseDtos.CategoryCount(e.getKey(), label(e.getKey()), e.getValue()))
                    .sorted(Comparator.comparingLong(ReportCaseDtos.CategoryCount::count).reversed())
                    .toList();
            User target = r.getTargetUserId() == null ? null : people.get(r.getTargetUserId());
            User assignee = r.getAssigneeId() == null ? null : people.get(r.getAssigneeId());
            String targetStatus = "USER".equals(r.getTargetType()) ? (target == null ? null : target.getStatus())
                    : posts.targetStatus(r.getTargetType(), r.getTargetId()).orElse(null);
            return new ReportCaseDtos.CaseView(r.getId(), r.getTargetType(), r.getTargetId(), targetStatus,
                    target == null ? null : target.getPublicUid(), target == null ? null : target.getNickname(),
                    r.getStatus(), r.getReportCount() == null ? 0 : r.getReportCount(), categories, r.getLatestSnapshot(),
                    AppTime.toOffset(r.getFirstReportedAt()), AppTime.toOffset(r.getLastReportedAt()),
                    assignee == null ? null : assignee.getPublicUid(), assignee == null ? null : assignee.getNickname(),
                    AppTime.toOffset(r.getClaimedAt()), r.getResolution(), r.getResolutionNote(),
                    AppTime.toOffset(r.getHandledAt()), r.getVersion() == null ? 0 : r.getVersion());
        }).toList();
    }
}
