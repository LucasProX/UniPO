package com.biecuoguo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
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
            LocalDateTime deadlineAt,
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
            LocalDateTime publishedAt,
            LocalDateTime createdAt,
            AuthorView author
    ) {}

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
            LocalDateTime deadlineAt,
            @Size(max = 500) String missConsequence,
            @Size(max = 500) String nextAction,
            List<String> tags
    ) {}

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
            LocalDateTime createdAt,
            AuthorView author
    ) {}

    public record InteractionNoticeView(
            Long id,
            String type,
            AuthorView actor,
            PostView post,
            String commentContent,
            Long parentCommentId,
            LocalDateTime createdAt,
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
