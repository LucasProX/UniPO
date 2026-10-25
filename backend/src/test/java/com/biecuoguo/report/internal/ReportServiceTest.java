package com.biecuoguo.report.internal;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.biecuoguo.config.AppProperties;
import com.biecuoguo.domain.User;
import com.biecuoguo.mapper.UserMapper;
import com.biecuoguo.permission.api.ScopeTarget;
import com.biecuoguo.post.api.PostQueryApi;
import com.biecuoguo.report.api.ReportDtos;
import com.biecuoguo.security.CurrentUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportServiceTest {
    private final ContentReportMapper mapper = mock(ContentReportMapper.class);
    private final PostQueryApi posts = mock(PostQueryApi.class);
    private final ReportCases cases = mock(ReportCases.class);
    private final ReportService service = new ReportService(mapper, mock(UserMapper.class), posts,
            new AppProperties(null, null, null, null, null, null, 1L), cases, mock(ReportCaseMapper.class));
    private final CurrentUser reporter = new CurrentUser(7L, "r@unipo.invalid", "USER", 1L, "s");
    private final ReportDtos.ReportRequest spam = new ReportDtos.ReportRequest("spam", "  一直发广告  ");

    @BeforeAll
    static void lambdaMetadata() {
        for (Class<?> type : new Class<?>[]{ContentReport.class, User.class}) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "report-tests"), type);
        }
    }

    @Test
    void reportingAPostStoresScopeAndSnapshot() {
        when(posts.reportable("POST", 3L, reporter)).thenReturn(Optional.of(
                new PostQueryApi.ReportableContent(3L, 9L, ScopeTarget.college(1, 10), "标题\n正文")));
        doAnswer(call -> { ((ContentReport) call.getArgument(0)).setId(11L); return 1; }).when(mapper).insert(any(ContentReport.class));
        ReportDtos.ReportReceipt receipt = service.reportContent("POST", 3L, spam, reporter);
        ArgumentCaptor<ContentReport> saved = ArgumentCaptor.forClass(ContentReport.class);
        verify(mapper).insert(saved.capture());
        assertEquals(11L, receipt.id());
        assertEquals("PENDING", receipt.status());
        assertEquals(9L, saved.getValue().getTargetUserId());
        assertEquals(10L, saved.getValue().getCollegeId());
        assertEquals("一直发广告", saved.getValue().getDetail());
        assertEquals("标题\n正文", saved.getValue().getSnapshot());
        assertEquals("USER", saved.getValue().getSource());
        verify(cases).attach(saved.getValue());
    }

    @Test
    void repeatedReportReturnsTheExistingReceipt() {
        when(posts.reportable("COMMENT", 5L, reporter)).thenReturn(Optional.of(
                new PostQueryApi.ReportableContent(5L, 9L, ScopeTarget.school(1), "评论")));
        ContentReport existing = new ContentReport();
        existing.setId(4L);
        existing.setStatus("PENDING");
        when(mapper.selectOne(any())).thenReturn(existing);
        assertEquals(4L, service.reportContent("COMMENT", 5L, spam, reporter).id());
        verify(mapper, never()).insert(any(ContentReport.class));
    }

    @Test
    void cannotReportYourselfInvisibleContentOrWithUnknownCategory() {
        when(posts.reportable("POST", 3L, reporter)).thenReturn(Optional.of(
                new PostQueryApi.ReportableContent(3L, 7L, ScopeTarget.school(1), "我自己的帖子")));
        assertThrows(IllegalArgumentException.class, () -> service.reportContent("POST", 3L, spam, reporter));
        when(posts.reportable("POST", 4L, reporter)).thenReturn(Optional.empty());
        assertThrows(NoSuchElementException.class, () -> service.reportContent("POST", 4L, spam, reporter));
        when(posts.reportable("POST", 6L, reporter)).thenReturn(Optional.of(
                new PostQueryApi.ReportableContent(6L, 9L, ScopeTarget.school(1), "x")));
        assertThrows(IllegalArgumentException.class,
                () -> service.reportContent("POST", 6L, new ReportDtos.ReportRequest("dislike", null), reporter));
        verify(mapper, never()).insert(any(ContentReport.class));
    }

    @Test
    void snapshotsAreClippedByCodePoint() {
        assertEquals(600, ReportService.clip("字".repeat(700)).length());
        assertNull(ReportService.clip(null));
    }
}
