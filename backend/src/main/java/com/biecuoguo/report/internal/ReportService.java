package com.biecuoguo.report.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.biecuoguo.config.AppProperties;
import com.biecuoguo.config.AppTime;
import com.biecuoguo.domain.User;
import com.biecuoguo.mapper.UserMapper;
import com.biecuoguo.permission.api.ScopeTarget;
import com.biecuoguo.post.api.PostQueryApi;
import com.biecuoguo.report.api.ReportCaseDtos;
import com.biecuoguo.report.api.ReportDtos;
import com.biecuoguo.security.CurrentUser;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.temporal.ChronoUnit;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * 用户举报用户、帖子、评论（F01）。同一个人对同一对象重复举报直接返回原来的回执，不重复建单。
 * 举报人看不到处理细节；处理在 O01 的受控接口里做。
 */
@Service
public class ReportService {
    static final Set<String> CATEGORIES = Set.of("spam", "fraud", "harassment", "porn", "impersonation", "other");
    private static final int SNAPSHOT_LIMIT = 600;

    private final ContentReportMapper mapper;
    private final UserMapper userMapper;
    private final PostQueryApi posts;
    private final AppProperties properties;
    private final ReportCases cases;
    private final ReportCaseMapper caseMapper;

    public ReportService(ContentReportMapper mapper, UserMapper userMapper, PostQueryApi posts, AppProperties properties,
                         ReportCases cases, ReportCaseMapper caseMapper) {
        this.cases = cases;
        this.caseMapper = caseMapper;
        this.mapper = mapper;
        this.userMapper = userMapper;
        this.posts = posts;
        this.properties = properties;
    }

    @Transactional
    public ReportDtos.ReportReceipt reportUser(String uid, ReportDtos.ReportRequest request, CurrentUser actor) {
        User target = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPublicUid, uid));
        if (target == null || !"active".equals(target.getStatus())) throw new NoSuchElementException("用户不存在");
        long school = target.getSchoolId() == null ? properties.defaultSchoolId() : target.getSchoolId();
        return file(actor, "USER", target.getId(), target.getId(), ScopeTarget.school(school), target.getNickname(), request);
    }

    @Transactional
    public ReportDtos.ReportReceipt reportContent(String type, long id, ReportDtos.ReportRequest request, CurrentUser actor) {
        PostQueryApi.ReportableContent content = posts.reportable(type, id, actor)
                .orElseThrow(() -> new NoSuchElementException("POST".equals(type) ? "PO 不存在" : "评论不存在"));
        return file(actor, type, content.id(), content.authorId(), content.scope(), content.snapshot(), request);
    }

    private ReportDtos.ReportReceipt file(CurrentUser actor, String type, long targetId, long targetUserId,
                                          ScopeTarget scope, String snapshot, ReportDtos.ReportRequest request) {
        if (Objects.equals(actor.id(), targetUserId)) throw new IllegalArgumentException("不能举报自己");
        String category = request.category() == null ? "" : request.category().trim();
        if (!CATEGORIES.contains(category)) throw new IllegalArgumentException("举报分类不合法");
        String detail = request.detail() == null ? null : request.detail().strip();
        ContentReport existing = existing(actor.id(), type, targetId);
        if (existing != null) return receipt(existing);
        ContentReport row = new ContentReport();
        row.setReporterId(actor.id());
        row.setSource("USER");
        row.setTargetType(type);
        row.setTargetId(targetId);
        row.setTargetUserId(targetUserId);
        row.setSchoolId(scope.schoolId());
        row.setCollegeId(scope.collegeId());
        row.setMajorId(scope.majorId());
        row.setCategory(category);
        row.setDetail(detail == null || detail.isEmpty() ? null : detail);
        row.setSnapshot(clip(snapshot));
        row.setStatus("PENDING");
        row.setCreatedAt(AppTime.nowLocal().truncatedTo(ChronoUnit.SECONDS));
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException race) {
            ContentReport winner = existing(actor.id(), type, targetId);
            if (winner == null) throw race;
            return receipt(winner);
        }
        cases.attach(row);
        return receipt(row);
    }

    /** 我提交过的举报和处理结果（O03）；状态以处理单为准，所以「处理中」也能看到。 */
    @Transactional(readOnly = true)
    public ReportCaseDtos.MyReportPage myReports(CurrentUser actor, Long beforeId) {
        int limit = 30;
        java.util.List<ContentReport> rows = mapper.selectList(new LambdaQueryWrapper<ContentReport>()
                .eq(ContentReport::getReporterId, actor.id())
                .lt(beforeId != null, ContentReport::getId, beforeId)
                .orderByDesc(ContentReport::getId)
                .last("LIMIT " + (limit + 1)));
        boolean more = rows.size() > limit;
        java.util.List<ContentReport> page = more ? rows.subList(0, limit) : rows;
        java.util.List<Long> caseIds = page.stream().map(ContentReport::getCaseId).filter(Objects::nonNull).distinct().toList();
        java.util.Map<Long, ReportCase> byId = caseIds.isEmpty() ? java.util.Map.of()
                : caseMapper.selectBatchIds(caseIds).stream().collect(java.util.stream.Collectors.toMap(ReportCase::getId, c -> c));
        java.util.List<ReportCaseDtos.MyReport> items = page.stream().map(r -> {
            ReportCase kase = r.getCaseId() == null ? null : byId.get(r.getCaseId());
            String status = kase != null ? kase.getStatus() : r.getStatus();
            String resolution = kase != null ? kase.getResolution() : r.getResolution();
            String excerpt = r.getSnapshot() == null ? null : r.getSnapshot().replace('\n', ' ');
            if (excerpt != null && excerpt.codePointCount(0, excerpt.length()) > 60) {
                excerpt = excerpt.substring(0, excerpt.offsetByCodePoints(0, 60)) + "…";
            }
            return new ReportCaseDtos.MyReport(r.getId(), r.getTargetType(), excerpt, r.getCategory(),
                    ReportCaseService.label(r.getCategory()), status, result(status, resolution),
                    AppTime.toOffset(r.getCreatedAt()), AppTime.toOffset(kase != null ? kase.getHandledAt() : r.getHandledAt()));
        }).toList();
        return new ReportCaseDtos.MyReportPage(items, more ? page.getLast().getId() : null);
    }

    static String result(String status, String resolution) {
        if ("PENDING".equals(status)) return "已受理，等待处理";
        if ("IN_PROGRESS".equals(status)) return "处理中";
        if ("REMOVED".equals(resolution)) return "已处理：内容已下架";
        if ("HANDLED".equals(resolution)) return "已处理";
        return "经核实未发现违规";
    }

    private ContentReport existing(long reporterId, String type, long targetId) {
        return mapper.selectOne(new LambdaQueryWrapper<ContentReport>()
                .eq(ContentReport::getReporterId, reporterId)
                .eq(ContentReport::getTargetType, type)
                .eq(ContentReport::getTargetId, targetId));
    }

    private static ReportDtos.ReportReceipt receipt(ContentReport row) {
        return new ReportDtos.ReportReceipt(row.getId(), row.getStatus());
    }

    static String clip(String text) {
        if (text == null) return null;
        String trimmed = text.strip();
        return trimmed.codePointCount(0, trimmed.length()) <= SNAPSHOT_LIMIT ? trimmed
                : trimmed.substring(0, trimmed.offsetByCodePoints(0, SNAPSHOT_LIMIT));
    }
}
