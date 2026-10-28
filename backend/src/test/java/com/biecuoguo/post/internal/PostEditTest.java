package com.biecuoguo.post.internal;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.biecuoguo.config.AppProperties;
import com.biecuoguo.domain.User;
import com.biecuoguo.error.ContentBlockedException;
import com.biecuoguo.mapper.*;
import com.biecuoguo.moderation.api.ContentFilterApi;
import com.biecuoguo.post.api.PostDtos;
import com.biecuoguo.report.api.ReportFlagApi;
import com.biecuoguo.security.CurrentUser;
import com.biecuoguo.service.ExperienceService;
import com.biecuoguo.service.PresenceService;
import com.biecuoguo.social.api.BlockQueryApi;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PostEditTest {
    private final PostMapper posts = mock(PostMapper.class);
    private final CommentMapper comments = mock(CommentMapper.class);
    private final PostImageMapper images = mock(PostImageMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final ReportFlagApi flags = mock(ReportFlagApi.class);
    private ContentFilterApi.Verdict verdict = ContentFilterApi.Verdict.ALLOW;
    private final CurrentUser author = new CurrentUser(30L, "author", "USER");
    private final CurrentUser stranger = new CurrentUser(31L, "stranger", "USER");
    private Post post;
    private PostService service;

    @BeforeAll
    static void lambdaMetadata() {
        for (Class<?> type : new Class<?>[]{Post.class, Comment.class, PostImage.class}) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "edit-tests"), type);
        }
    }

    @BeforeEach
    void setUp() {
        post = new Post();
        post.setId(70L);
        post.setAuthorId(30L);
        post.setSchoolId(1L);
        post.setBoard("school");
        post.setStatus("published");
        when(posts.lockById(70L)).thenReturn(post);
        User user = new User();
        user.setId(30L);
        user.setStatus("active");
        user.setSchoolId(1L);
        when(users.lockById(30L)).thenReturn(user);
        service = new PostService(mock(AppProperties.class), posts, images,
                mock(PostLikeMapper.class), mock(PostFavoriteMapper.class), mock(PostShareMapper.class),
                mock(PostDailyStatMapper.class), comments, mock(CommentLikeMapper.class), users,
                mock(SchoolMapper.class), mock(CollegeMapper.class), mock(MajorMapper.class),
                mock(PresenceService.class), mock(ExperienceService.class), mock(BlockQueryApi.class),
                mock(com.biecuoguo.admin.api.AdminAuditApi.class),
                mock(com.biecuoguo.activity.api.ActivityParticipationApi.class), mock(com.biecuoguo.achievement.api.TaskLookupApi.class),
                mock(com.biecuoguo.notification.api.NotificationApi.class), mock(UserFollowMapper.class), new com.biecuoguo.permission.api.PermissionApi() {
                    public com.biecuoguo.permission.api.Grants grantsOf(Long userId) { return com.biecuoguo.permission.api.Grants.none(); }
                    public boolean hasScopedGrant(long userId) { return false; }
                }, new ContentFilterApi() {
                    public Verdict check(String... texts) { return verdict; }
                    public boolean reviewAllPosts() { return false; }
                }, flags);
    }

    private PostDtos.UpdatePostRequest edit(String content) {
        return new PostDtos.UpdatePostRequest("新标题", content, List.of("/api/media/1/content?v=a.jpg"), List.of("四级", "四级"), null, null);
    }

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<Post> savedPost() {
        ArgumentCaptor<LambdaUpdateWrapper<Post>> update = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(posts).update(isNull(), update.capture());
        return update.getValue();
    }

    private void tryUpdate(CurrentUser user, String content) {
        // 编辑写完后还要组装返回视图，这里只验证写入，组装失败不影响断言。
        try { service.update(70L, edit(content), user); } catch (NullPointerException ignored) { }
    }

    @Test
    void onlyTheAuthorCanEdit() {
        assertThrows(AccessDeniedException.class, () -> service.update(70L, edit("x"), stranger));
        post.setStatus("deleted");
        assertThrows(NoSuchElementException.class, () -> service.update(70L, edit("x"), author));
        verify(posts, never()).update(any(), any());
    }

    @Test
    void cleanEditKeepsThePostPublishedAndRecordsTheEditTime() {
        tryUpdate(author, "改过的正文");
        LambdaUpdateWrapper<Post> update = savedPost();
        assertThat(update.getSqlSet()).contains("edited_at").contains("status").doesNotContain("published_at");
        assertThat(update.getParamNameValuePairs().values()).contains("published", "四级", "normal");
        verify(images).delete(any());
        verify(images).insert(any(PostImage.class));
        verify(flags, never()).flag(any(), anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void suspiciousEditSendsAPublishedPostBackToReview() {
        verdict = new ContentFilterApi.Verdict(ContentFilterApi.Level.REVIEW, "contact_lure");
        tryUpdate(author, "加微信");
        assertThat(savedPost().getParamNameValuePairs().values()).contains("pending", "review");
        verify(flags).flag(eq("POST"), eq(70L), eq(30L), any(), eq("contact_lure"), contains("加微信"));
    }

    @Test
    void editingAPendingPostDoesNotPublishIt() {
        post.setStatus("pending");
        tryUpdate(author, "改成正常内容");
        assertThat(savedPost().getParamNameValuePairs().values()).contains("pending").doesNotContain("published");
    }

    @Test
    void blockedWordsAreRejected() {
        verdict = new ContentFilterApi.Verdict(ContentFilterApi.Level.BLOCK, "abuse");
        assertThrows(ContentBlockedException.class, () -> service.update(70L, edit("违禁"), author));
        verify(posts, never()).update(any(), any());
    }

    private Comment comment(String status, Long parent) {
        Comment comment = new Comment();
        comment.setId(80L);
        comment.setPostId(70L);
        comment.setUserId(30L);
        comment.setParentId(parent);
        comment.setStatus(status);
        when(comments.lockById(80L)).thenReturn(comment);
        return comment;
    }

    @Test
    void authorsDeleteTheirOwnCommentsAndCountsFollow() {
        comment("visible", 79L);
        service.deleteOwnComment(80L, author);
        verify(posts).adjustCounters(eq(70L), eq(0), eq(-1), eq(0), eq(0), any(LocalDateTime.class));
        verify(comments).decrementReplies(eq(79L), any(LocalDateTime.class));
    }

    @Test
    void othersCommentsAndInvisibleCommentsCannotBeDeleted() {
        comment("visible", null);
        assertThrows(AccessDeniedException.class, () -> service.deleteOwnComment(80L, stranger));
        comment("deleted", null);
        assertThrows(NoSuchElementException.class, () -> service.deleteOwnComment(80L, author));
        comment("hidden", null);
        assertThrows(NoSuchElementException.class, () -> service.deleteOwnComment(80L, author));
        verify(posts, never()).adjustCounters(anyLong(), anyInt(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void tagsAreNormalized() {
        assertEquals("四级", PostService.normalizeTag("  #四级 "));
        assertEquals("考研", PostService.normalizeTag("＃考研"));
        assertThrows(IllegalArgumentException.class, () -> PostService.normalizeTag("#"));
        assertThrows(IllegalArgumentException.class, () -> PostService.normalizeTag("a,b"));
        assertThrows(IllegalArgumentException.class, () -> PostService.normalizeTag("一".repeat(21)));
        assertEquals(2, PostService.INVISIBLE_COMMENT.size());
    }
}
