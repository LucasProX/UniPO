package com.biecuoguo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.biecuoguo.domain.Comment;
import com.biecuoguo.domain.User;
import com.biecuoguo.dto.CommentDtos;
import com.biecuoguo.mapper.CommentMapper;
import com.biecuoguo.mapper.UserMapper;
import com.biecuoguo.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@Service
public class CommentService {
    private final CommentMapper commentMapper;
    private final UserMapper userMapper;

    public CommentService(CommentMapper commentMapper, UserMapper userMapper) {
        this.commentMapper = commentMapper;
        this.userMapper = userMapper;
    }

    public List<CommentDtos.CommentView> list(Long noticeId, String sort) {
        List<Comment> comments = commentMapper.selectList(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getNoticeId, noticeId)
                .ne(Comment::getStatus, "hidden"));
        if ("latest".equals(sort)) {
            comments.sort(Comparator.comparing(Comment::getCreatedAt).reversed());
        } else {
            comments.sort(Comparator.comparing(Comment::getFeatured).reversed()
                    .thenComparing(Comment::getLikeCount, Comparator.reverseOrder())
                    .thenComparing(Comment::getCreatedAt, Comparator.reverseOrder()));
        }
        Map<Long, User> users = userMapper.selectBatchIds(comments.stream().map(Comment::getUserId).collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(User::getId, u -> u));
        return comments.stream()
                .filter(comment -> users.containsKey(comment.getUserId()))
                .map(comment -> CommentDtos.CommentView.from(comment, users.get(comment.getUserId())))
                .toList();
    }

    @Transactional
    public CommentDtos.CommentView create(Long noticeId, CommentDtos.CommentRequest request, CurrentUser currentUser) {
        LocalDateTime now = LocalDateTime.now();
        Comment comment = new Comment();
        comment.setNoticeId(noticeId);
        comment.setUserId(currentUser.id());
        comment.setParentId(request.parentId());
        comment.setContent(request.content());
        comment.setLikeCount(0);
        comment.setReplyCount(0);
        comment.setFeatured(false);
        comment.setStatus("visible");
        comment.setCreatedAt(now);
        comment.setUpdatedAt(now);
        commentMapper.insert(comment);
        User user = userMapper.selectById(currentUser.id());
        return CommentDtos.CommentView.from(comment, user);
    }

    @Transactional
    public CommentDtos.CommentView like(Long id) {
        Comment comment = requireComment(id);
        comment.setLikeCount(comment.getLikeCount() + 1);
        comment.setUpdatedAt(LocalDateTime.now());
        commentMapper.updateById(comment);
        return CommentDtos.CommentView.from(comment, userMapper.selectById(comment.getUserId()));
    }

    @Transactional
    public CommentDtos.CommentView unlike(Long id) {
        Comment comment = requireComment(id);
        comment.setLikeCount(Math.max(0, comment.getLikeCount() - 1));
        comment.setUpdatedAt(LocalDateTime.now());
        commentMapper.updateById(comment);
        return CommentDtos.CommentView.from(comment, userMapper.selectById(comment.getUserId()));
    }

    @Transactional
    public void report(Long id) {
        requireComment(id);
    }

    public List<CommentDtos.CommentView> adminList(Long noticeId, String status) {
        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<>();
        if (noticeId != null) wrapper.eq(Comment::getNoticeId, noticeId);
        if (status != null && !status.isBlank()) wrapper.eq(Comment::getStatus, status);
        wrapper.orderByDesc(Comment::getCreatedAt);
        List<Comment> comments = commentMapper.selectList(wrapper);
        Map<Long, User> users = userMapper.selectBatchIds(comments.stream().map(Comment::getUserId).collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(User::getId, u -> u));
        return comments.stream()
                .filter(comment -> users.containsKey(comment.getUserId()))
                .map(comment -> CommentDtos.CommentView.from(comment, users.get(comment.getUserId())))
                .toList();
    }

    @Transactional
    public CommentDtos.CommentView hide(Long id) {
        Comment comment = requireComment(id);
        comment.setStatus("hidden");
        comment.setUpdatedAt(LocalDateTime.now());
        commentMapper.updateById(comment);
        return CommentDtos.CommentView.from(comment, userMapper.selectById(comment.getUserId()));
    }

    @Transactional
    public CommentDtos.CommentView feature(Long id) {
        Comment comment = requireComment(id);
        comment.setFeatured(true);
        comment.setUpdatedAt(LocalDateTime.now());
        commentMapper.updateById(comment);
        return CommentDtos.CommentView.from(comment, userMapper.selectById(comment.getUserId()));
    }

    private Comment requireComment(Long id) {
        Comment comment = commentMapper.selectById(id);
        if (comment == null) {
            throw new NoSuchElementException("评论不存在");
        }
        return comment;
    }
}
