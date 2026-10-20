package com.biecuoguo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.biecuoguo.domain.Comment;
import com.biecuoguo.domain.Post;
import com.biecuoguo.domain.User;
import com.biecuoguo.dto.AdminDtos;
import com.biecuoguo.mapper.CommentMapper;
import com.biecuoguo.mapper.PostMapper;
import com.biecuoguo.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AdminService {
    private final UserMapper userMapper;
    private final PostMapper postMapper;
    private final CommentMapper commentMapper;

    public AdminService(UserMapper userMapper, PostMapper postMapper, CommentMapper commentMapper) {
        this.userMapper = userMapper;
        this.postMapper = postMapper;
        this.commentMapper = commentMapper;
    }

    /**
     * 管理端概览。notices 域已整体删除，这里只统计 posts 侧的数据。
     */
    public AdminDtos.AnalyticsOverview overview() {
        return new AdminDtos.AnalyticsOverview(
                userMapper.selectCount(null),
                postMapper.selectCount(null),
                postMapper.selectCount(new LambdaQueryWrapper<Post>().eq(Post::getStatus, "published")),
                commentMapper.selectCount(new LambdaQueryWrapper<Comment>().isNotNull(Comment::getPostId))
        );
    }

    public List<User> users() {
        return userMapper.selectList(new LambdaQueryWrapper<User>().orderByDesc(User::getCreatedAt));
    }

    @Transactional
    public User updateUserStatus(Long id, AdminDtos.UserStatusRequest request) {
        User user = userMapper.selectById(id);
        user.setStatus(request.status());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        return user;
    }

    @Transactional
    public User updateUserRole(Long id, AdminDtos.UserRoleRequest request) {
        User user = userMapper.selectById(id);
        user.setRole(request.role());
        user.setOperatorScope(request.operatorScope());
        user.setVerifiedStatus("USER".equals(request.role()) ? "none" : "verified");
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        return user;
    }
}