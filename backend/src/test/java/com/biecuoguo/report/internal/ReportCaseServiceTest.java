package com.biecuoguo.report.internal;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.biecuoguo.admin.api.AdminAuditApi;
import com.biecuoguo.domain.User;
import com.biecuoguo.error.StateConflictException;
import com.biecuoguo.mapper.UserMapper;
import com.biecuoguo.notification.api.NotificationApi;
import com.biecuoguo.permission.api.Grant;
import com.biecuoguo.permission.api.Grants;
import com.biecuoguo.permission.api.PermissionApi;
import com.biecuoguo.permission.api.Role;
import com.biecuoguo.permission.api.ScopeType;
import com.biecuoguo.post.api.PostAdminApi;
import com.biecuoguo.report.api.ReportCaseDtos;
import com.biecuoguo.security.CurrentUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportCaseServiceTest {
    private final ReportCaseMapper cases = mock(ReportCaseMapper.class);
    private final ContentReportMapper reports = mock(ContentReportMapper.class);
    private final PostAdminApi posts = mock(PostAdminApi.class);
    private final PermissionApi permissions = mock(PermissionApi.class);
    private final AdminAuditApi audit = mock(AdminAuditApi.class);
    private final NotificationApi notifications = mock(NotificationApi.class);
    private final ReportCaseService service = new ReportCaseService(cases, reports, mock(UserMapper.class), posts,
            permissions, audit, notifications);
    private final CurrentUser moderator = new CurrentUser(2L, "m@unipo.invalid", "USER", 1L, "s");
    private final CurrentUser other = new CurrentUser(3L, "o@unipo.invalid", "USER", 1L, "s");
    private ReportCase kase;

    @BeforeAll
    static void lambdaMetadata() {
        for (Class<?> type : new Class<?>[]{ReportCase.class, ContentReport.class, User.class}) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "case-tests"), type);
        }
    }

    @BeforeEach
    void setUp() {
        kase = new ReportCase();
        kase.setId(5L);
        kase.setTargetType("POST");
        kase.setTargetId(40L);
        kase.setTargetUserId(9L);
        kase.setSchoolId(1L);
        kase.setStatus("PENDING");
        kase.setVersion(0);
        when(cases.selectById(5L)).thenReturn(kase);
        Grants schoolModerator = new Grants(false, List.of(new Grant(1, Role.MODERATOR, ScopeType.SCHOOL, 1L, 1L, null)));
        when(permissions.grantsOf(moderator)).thenReturn(schoolModerator);
        when(permissions.grantsOf(other)).thenReturn(schoolModerator);
        doCallRealMethod().when(permissions).can(any(), any(), any());
        doCallRealMethod().when(permissions).require(any(), any(), any());
        when(cases.update(isNull(), any())).thenReturn(1);
        when(posts.targetStatus("POST", 40L)).thenReturn(Optional.of("published"));
        ContentReport report = new ContentReport();
        report.setReporterId(7L);
        report.setCategory("spam");
        report.setCaseId(5L);
        when(reports.selectList(any())).thenReturn(List.of(report));
    }

    private ReportCaseDtos.ResolveRequest resolve(String action) {
        return new ReportCaseDtos.ResolveRequest(0, action, "核实为广告");
    }

    @Test
    void outsideTheGrantScopeIsDenied() {
        kase.setSchoolId(2L);
        assertThrows(AccessDeniedException.class, () -> service.claim(moderator, 5L, new ReportCaseDtos.ClaimRequest(0)));
        assertThrows(AccessDeniedException.class, () -> service.resolve(moderator, 5L, resolve("NO_VIOLATION")));
        verify(cases, never()).update(any(), any());
    }

    @Test
    void moderatorsCannotHandleReportsAboutThemselves() {
        kase.setTargetUserId(moderator.id());
        assertThrows(StateConflictException.class, () -> service.claim(moderator, 5L, new ReportCaseDtos.ClaimRequest(0)));
        assertThrows(StateConflictException.class, () -> service.detail(moderator, 5L));
        verify(cases, never()).update(any(), any());
    }

    @Test
    void claimedByAnotherModeratorIsAConflict() {
        kase.setStatus("IN_PROGRESS");
        kase.setAssigneeId(3L);
        assertThrows(StateConflictException.class, () -> service.claim(moderator, 5L, new ReportCaseDtos.ClaimRequest(0)));
        assertThrows(StateConflictException.class, () -> service.resolve(moderator, 5L, resolve("NO_VIOLATION")));
    }

    @Test
    void staleVersionRollsBackTheWholeResolution() {
        when(cases.update(isNull(), any())).thenReturn(0);
        assertThrows(StateConflictException.class, () -> service.resolve(moderator, 5L, resolve("REMOVE_CONTENT")));
        verify(audit, never()).recordRequired(any(), any(), any(), any(), any());
        verify(notifications, never()).notifySystem(any(), any(), any(), any(), any(), any());
    }

    @Test
    void removingAPostNotifiesReporterAndAuthorAndAudits() {
        service.resolve(moderator, 5L, resolve("REMOVE_CONTENT"));
        verify(posts).moderate(40L, null, null, null, "removed", moderator);
        verify(audit).recordRequired(eq(moderator), eq("REPORT_RESOLVE"), eq("report_case"), eq(5L), contains("REMOVE_CONTENT"));
        verify(notifications).notifySystem(eq(Set.of(7L)), eq("REPORT_RESOLVED"), any(), contains("已被下架"), isNull(), eq("report-case:5"));
        verify(notifications).notifySystem(eq(List.of(9L)), eq("CONTENT_REMOVED"), any(), contains("垃圾广告"), isNull(), eq("removed:5"));
    }

    @Test
    void alreadyDeletedPostIsNotModeratedAgain() {
        when(posts.targetStatus("POST", 40L)).thenReturn(Optional.of("deleted"));
        service.resolve(moderator, 5L, resolve("REMOVE_CONTENT"));
        verify(posts, never()).moderate(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void commentsAreHiddenAndUsersCannotBeRemoved() {
        kase.setTargetType("COMMENT");
        service.resolve(moderator, 5L, resolve("REMOVE_CONTENT"));
        verify(posts).hideComment(40L, moderator);
        kase.setTargetType("USER");
        assertThrows(IllegalArgumentException.class, () -> service.resolve(moderator, 5L, resolve("REMOVE_CONTENT")));
    }

    @Test
    void onlyPendingPostsCanBeApproved() {
        assertThrows(IllegalArgumentException.class, () -> service.resolve(moderator, 5L, resolve("APPROVE_CONTENT")));
        when(posts.targetStatus("POST", 40L)).thenReturn(Optional.of("pending"));
        service.resolve(moderator, 5L, resolve("APPROVE_CONTENT"));
        verify(posts).moderate(40L, null, null, null, "published", moderator);
        verify(notifications).notifySystem(eq(List.of(9L)), eq("POST_APPROVED"), any(), any(), eq(40L), eq("approved:5"));
    }

    @Test
    void unknownActionAndClosedCasesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.resolve(moderator, 5L, resolve("BAN")));
        kase.setStatus("RESOLVED");
        assertThrows(StateConflictException.class, () -> service.resolve(moderator, 5L, resolve("NO_VIOLATION")));
    }

    @Test
    void labelsAndReporterMessages() {
        assertEquals("疑似违规内容（系统识别）", ReportCaseService.label("filter:contact_lure"));
        assertEquals("骚扰或人身攻击", ReportCaseService.label("harassment"));
        assertTrue(ReportCaseService.reporterMessage("NO_VIOLATION").contains("暂未发现违规"));
        assertEquals("处理中", ReportService.result("IN_PROGRESS", null));
        assertEquals("已处理：内容已下架", ReportService.result("RESOLVED", "REMOVED"));
    }
}
