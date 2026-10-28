package com.biecuoguo.post.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

public final class PostDtos {
    private PostDtos() {}

    public record AuthorView(
            Long id,
            String uid,
            String nickname,
            String avatarUrl,
            String role,
            String operatorScope,
            Integer level,
            String levelTitle,
            String school,
            String college,
            String major,
            String grade,
            Boolean online
    ) {}

    public record PostView(
            Long id,
            String board,
            String sourceType,
            String title,
            String content,
            String excerpt,
            String coverUrl,
            List<String> images,
            String riskLevel,
            Boolean official,
            Boolean dontMiss,
            Boolean pinned,
            OffsetDateTime deadlineAt,
            String missConsequence,
            String nextAction,
            List<String> tags,
            Integer likeCount,
            Integer commentCount,
            Integer favoriteCount,
            Integer shareCount,
            Boolean liked,
            Boolean favorited,
            String status,
            OffsetDateTime publishedAt,
            OffsetDateTime createdAt,
            AuthorView author,
            /** 运营最近一次核实日期，yyyy-MM-dd；未核实为空。 */
            String verifiedAt,
            /** 攻略有效期；不设为空。 */
            OffsetDateTime expiresAt,
            /** 已过有效期。过期帖子照常可读，但不再作为任务的有效攻略。 */
            Boolean expired,
            /** 关联的校园活动；没有为空。 */
            LinkRef activity,
            /** 关联的主线任务；没有为空。 */
            LinkRef task,
            /** 作者最近一次编辑的时间；没编辑过为空（F13）。 */
            OffsetDateTime editedAt
    ) {}

    /** 帖子挂载的活动或任务：只给 ID 和标题，点开后再读各自的详情。 */
    public record LinkRef(long id, String title) {}

    public record CreatePostRequest(
            @NotBlank @Size(max = 30) String board,
            @NotBlank @Size(max = 120) String title,
            @NotBlank @Size(max = 5000) String content,
            @Size(max = 500) String coverUrl,
            List<String> images,
            @Size(max = 20) String riskLevel,
            Boolean official,
            Boolean dontMiss,
            Boolean pinned,
            OffsetDateTime deadlineAt,
            @Size(max = 500) String missConsequence,
            @Size(max = 500) String nextAction,
            List<String> tags,
            /** 挂一个本校校园活动（签到后「发帖记录」）。 */
            Long activityId,
            /** 挂一个主线任务（任务详情里「写经验」）。 */
            Long taskId
    ) {}

    /**
     * 作者编辑自己的帖子（F13）：只改内容、图片、标签和关联，不改板块、官方标记和发布时间。
     * images、tags 整体替换；activityId / taskId 为空表示去掉关联。
     */
    public record UpdatePostRequest(
            @NotBlank @Size(max = 120) String title,
            @NotBlank @Size(max = 5000) String content,
            List<String> images,
            List<String> tags,
            Long activityId,
            Long taskId
    ) {}

    /** 标签页头部（F09）：标签名、同校可见的帖子数、带这个标签的帖子里最常关联的主线任务。 */
    public record TagSummaryView(String name, long postCount, List<LinkRef> tasks) {}

    public record CreateCommentRequest(@NotBlank @Size(max = 1000) String content, Long parentId) {}

    public record CommentView(
            Long id,
            Long postId,
            Long userId,
            Long parentId,
            String content,
            Integer likeCount,
            Integer replyCount,
            Boolean liked,
            Boolean featured,
            String status,
            OffsetDateTime createdAt,
            AuthorView author
    ) {}

    public record InteractionNoticeView(
            Long id,
            String type,
            AuthorView actor,
            PostView post,
            String commentContent,
            Long parentCommentId,
            OffsetDateTime createdAt,
            Boolean unread
    ) {}

    public record BoardView(String code, String name, String description, Long count) {}

    /**
     * 游标分页结果。
     *
     * 为什么用游标而不是 page/size：offset 分页在 feed 有新内容插入时会
     * 重复或漏条，App 的无限滚动体验会崩。nextCursor 编码「上一页最后一条」
     * 的完整排序键（keyset pagination），hasMore 表示是否还有更多。
     *
     * 老契约（直接返回数组）保留在 /api 上，/api/v1 起新端点统一用这个包装。
     */
    public record PostPageView(
            List<PostView> items,
            String nextCursor,
            Boolean hasMore,
            Integer limit
    ) {
        public static PostPageView of(List<PostView> items, String nextCursor, int limit) {
            return new PostPageView(items, nextCursor, nextCursor != null, limit);
        }
    }

    public record ShareResponse(String url, Integer shareCount) {}
}
