package com.biecuoguo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.biecuoguo.security.SessionService;
import com.biecuoguo.domain.User;
import com.biecuoguo.dto.AdminDtos;
import com.biecuoguo.mapper.UserMapper;
import com.biecuoguo.permission.api.RoleGrantApi;
import com.biecuoguo.post.api.PostQueryApi;
import com.biecuoguo.security.CurrentUser;
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
    private final RoleGrantApi roleGrants;

    public AdminService(UserMapper userMapper, PostQueryApi postQueryApi, SessionService sessions, RoleGrantApi roleGrants) {
        this.roleGrants = roleGrants;
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
    /**
     * 旧的改角色接口，保留兼容：运营角色会转成按此人当前资料范围的授权（ADR-0006），
     * 审计在同一事务里必需写入。新代码用 /api/admin/role-grants。
     */
    public User updateUserRole(Long id, AdminDtos.UserRoleRequest request, CurrentUser actor) {
        roleGrants.applyLegacyRole(actor, id, request.role(), request.operatorScope());
        sessions.revokeAllForUser(id);
        User user = userMapper.selectById(id);
        if (user == null) { throw new java.util.NoSuchElementException("用户不存在"); }
        return user;
    }
}
