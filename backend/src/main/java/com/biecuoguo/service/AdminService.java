package com.biecuoguo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.biecuoguo.domain.*;
import com.biecuoguo.dto.AdminDtos;
import com.biecuoguo.mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AdminService {
    private final UserMapper userMapper;
    private final NoticeMapper noticeMapper;
    private final CommentMapper commentMapper;
    private final FavoriteMapper favoriteMapper;
    private final ReminderMapper reminderMapper;

    public AdminService(UserMapper userMapper, NoticeMapper noticeMapper, CommentMapper commentMapper, FavoriteMapper favoriteMapper, ReminderMapper reminderMapper) {
        this.userMapper = userMapper;
        this.noticeMapper = noticeMapper;
        this.commentMapper = commentMapper;
        this.favoriteMapper = favoriteMapper;
        this.reminderMapper = reminderMapper;
    }

    public AdminDtos.AnalyticsOverview overview() {
        return new AdminDtos.AnalyticsOverview(
                userMapper.selectCount(null),
                noticeMapper.selectCount(null),
                noticeMapper.selectCount(new LambdaQueryWrapper<Notice>().eq(Notice::getStatus, "published")),
                commentMapper.selectCount(null),
                favoriteMapper.selectCount(null),
                reminderMapper.selectCount(null)
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
