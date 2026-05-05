package com.biecuoguo.dto;

import com.biecuoguo.domain.Comment;
import com.biecuoguo.domain.User;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public final class CommentDtos {
    private CommentDtos() {}

    public record CommentRequest(Long parentId, @NotBlank @Size(max = 1000) String content) {}

    public record CommentView(
            Long id,
            Long noticeId,
            Long userId,
            Long parentId,
            String nickname,
            String avatarUrl,
            String school,
            String grade,
            String major,
            String identityTag,
            String content,
            Integer likeCount,
            Integer replyCount,
            Boolean featured,
            String status,
            LocalDateTime createdAt
    ) {
        public static CommentView from(Comment comment, User user) {
            String identity = user.getVerifiedStatus() != null && !user.getVerifiedStatus().equals("none")
                    ? "认证学长学姐"
                    : "同学提醒";
            return new CommentView(
                    comment.getId(),
                    comment.getNoticeId(),
                    comment.getUserId(),
                    comment.getParentId(),
                    user.getNickname(),
                    user.getAvatarUrl(),
                    "试点大学",
                    user.getGrade(),
                    user.getMajor(),
                    identity,
                    comment.getContent(),
                    comment.getLikeCount(),
                    comment.getReplyCount(),
                    comment.getFeatured(),
                    comment.getStatus(),
                    comment.getCreatedAt()
            );
        }
    }
}
