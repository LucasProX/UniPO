package com.biecuoguo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.biecuoguo.domain.User;
import com.biecuoguo.dto.AdminDtos;
import com.biecuoguo.mapper.UserMapper;
import com.biecuoguo.post.api.PostQueryApi;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AdminService {
    private final UserMapper userMapper;
    private final PostQueryApi postQueryApi;

    public AdminService(UserMapper userMapper, PostQueryApi postQueryApi) {
        this.userMapper = userMapper;
        this.postQueryApi = postQueryApi;
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
