package com.biecuoguo.post.internal;

import com.biecuoguo.config.AppProperties;
import com.biecuoguo.mapper.*;
import com.biecuoguo.post.api.PostDtos;
import com.biecuoguo.security.CurrentUser;
import com.biecuoguo.service.ExperienceService;
import com.biecuoguo.service.PresenceService;
import com.biecuoguo.social.api.BlockQueryApi;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostServiceBlockTest {

    @Test
    void replyingToACommentAuthorWhoBlockedTheViewerHasNoWriteOrRewardSideEffects() {
        PostMapper posts = mock(PostMapper.class);
        CommentMapper comments = mock(CommentMapper.class);
        ExperienceService experience = mock(ExperienceService.class);
        BlockQueryApi blocks = mock(BlockQueryApi.class);
        Post post = new Post();
        post.setId(70L);
        post.setAuthorId(30L);
        post.setStatus("published");
        when(posts.lockById(70L)).thenReturn(post);
        Comment parent = new Comment();
        parent.setId(80L);
        parent.setPostId(70L);
        parent.setUserId(40L);
        parent.setStatus("visible");
        when(comments.lockById(80L)).thenReturn(parent);
        when(blocks.isBlockedEitherWay(10L, 30L)).thenReturn(false);
        when(blocks.isBlockedEitherWay(10L, 40L)).thenReturn(true);

        PostService service = new PostService(mock(AppProperties.class), posts, mock(PostImageMapper.class),
                mock(PostLikeMapper.class), mock(PostFavoriteMapper.class), mock(PostShareMapper.class),
                mock(PostDailyStatMapper.class), comments, mock(CommentLikeMapper.class), mock(UserMapper.class),
                mock(SchoolMapper.class), mock(CollegeMapper.class), mock(MajorMapper.class),
                mock(PresenceService.class), experience, blocks, mock(com.biecuoguo.admin.api.AdminAuditApi.class),
                mock(com.biecuoguo.activity.api.ActivityParticipationApi.class), mock(com.biecuoguo.achievement.api.TaskLookupApi.class),
                mock(com.biecuoguo.notification.api.NotificationApi.class), mock(UserFollowMapper.class), new com.biecuoguo.permission.api.PermissionApi() {
                    public com.biecuoguo.permission.api.Grants grantsOf(Long userId) { return com.biecuoguo.permission.api.Grants.none(); }
                    public boolean hasScopedGrant(long userId) { return false; }
                });

        assertThrows(NoSuchElementException.class, () -> service.comment(70L,
                new PostDtos.CreateCommentRequest("Blocked reply", 80L),
                new CurrentUser(10L, "viewer", "USER"), "blocked-parent"));

        verify(comments, never()).insert(any(Comment.class));
        verify(comments, never()).incrementReplies(eq(80L), any(LocalDateTime.class));
        verify(posts, never()).adjustCounters(anyLong(), anyInt(), anyInt(), anyInt(), anyInt(), any(LocalDateTime.class));
        verify(experience, never()).award(anyLong(), anyInt());
    }
}
