package com.biecuoguo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.biecuoguo.security.SessionService;
import com.biecuoguo.domain.User;
import com.biecuoguo.dto.AdminDtos;
import com.biecuoguo.mapper.UserMapper;
import com.biecuoguo.post.api.PostQueryApi;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AdminService {
    private final UserMapper userMapper;
    private final PostQueryApi postQueryApi;
    private final SessionService sessions;

    public AdminService(UserMapper userMapper, PostQueryApi postQueryApi, SessionService sessions) {
        this.userMapper = userMapper;
        this.postQueryApi = postQueryApi;
        this.sessions = sessions;
    }

    /**
     * 管理端概览。notices 域已整体删除，这里只统计 posts 侧的数据。
     *
     * <p>帖子相关的计数走 {@link PostQueryApi}，不直接注入 PostMapper ——
     * 否则后台会和帖子表结构永久耦合（ADR-0002）。
     */
    public AdminDtos.AnalyticsOverview overview() {
        PostQueryApi.AdminPostStats stats = postQueryApi.adminStats();
        return new AdminDtos.AnalyticsOverview(
                userMapper.selectCount(null),
                stats.posts(),
                stats.publishedPosts(),
                stats.comments()
        );
    }

    @PreAuthorize("hasRole('ADMIN')")
    public List<User> users() {
        return userMapper.selectList(new LambdaQueryWrapper<User>().orderByDesc(User::getCreatedAt));
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public User updateUserStatus(Long id, AdminDtos.UserStatusRequest request) {
        User user = userMapper.selectById(id);
        if (user == null) { throw new java.util.NoSuchElementException("用户不存在"); }
        user.setStatus(request.status());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.update(null, new LambdaUpdateWrapper<User>().eq(User::getId, id)
                .set(User::getStatus, user.getStatus()).set(User::getUpdatedAt, user.getUpdatedAt()));
        sessions.revokeAllForUser(id);
        return user;
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public User updateUserRole(Long id, AdminDtos.UserRoleRequest request) {
        User user = userMapper.selectById(id);
        if (user == null) { throw new java.util.NoSuchElementException("用户不存在"); }
        user.setRole(request.role());
        user.setOperatorScope(request.operatorScope());
        user.setVerifiedStatus("USER".equals(request.role()) ? "none" : "verified");
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.update(null, new LambdaUpdateWrapper<User>().eq(User::getId, id)
                .set(User::getRole, user.getRole()).set(User::getOperatorScope, user.getOperatorScope())
                .set(User::getVerifiedStatus, user.getVerifiedStatus()).set(User::getUpdatedAt, user.getUpdatedAt()));
        sessions.revokeAllForUser(id);
        return user;
    }
}
