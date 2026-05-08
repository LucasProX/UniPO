package com.biecuoguo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.biecuoguo.domain.User;
import com.biecuoguo.domain.UserFollow;
import com.biecuoguo.mapper.UserFollowMapper;
import com.biecuoguo.mapper.UserMapper;
import com.biecuoguo.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.NoSuchElementException;

@Service
public class SocialService {
    private final UserMapper userMapper;
    private final UserFollowMapper followMapper;

    public SocialService(UserMapper userMapper, UserFollowMapper followMapper) {
        this.userMapper = userMapper;
        this.followMapper = followMapper;
    }

    @Transactional
    public boolean follow(String uid, CurrentUser currentUser) {
        User target = userByUid(uid);
        if (target.getId().equals(currentUser.id())) {
            throw new IllegalArgumentException("不能关注自己");
        }
        UserFollow existing = followMapper.selectOne(new LambdaQueryWrapper<UserFollow>()
                .eq(UserFollow::getFollowerId, currentUser.id())
                .eq(UserFollow::getFollowingId, target.getId()));
        if (existing != null) {
            return true;
        }
        UserFollow follow = new UserFollow();
        follow.setFollowerId(currentUser.id());
        follow.setFollowingId(target.getId());
        follow.setCreatedAt(LocalDateTime.now());
        followMapper.insert(follow);
        return true;
    }

    @Transactional
    public boolean unfollow(String uid, CurrentUser currentUser) {
        User target = userByUid(uid);
        followMapper.delete(new LambdaQueryWrapper<UserFollow>()
                .eq(UserFollow::getFollowerId, currentUser.id())
                .eq(UserFollow::getFollowingId, target.getId()));
        return true;
    }

    public long followingCount(Long userId) {
        return followMapper.selectCount(new LambdaQueryWrapper<UserFollow>().eq(UserFollow::getFollowerId, userId));
    }

    public long followerCount(Long userId) {
        return followMapper.selectCount(new LambdaQueryWrapper<UserFollow>().eq(UserFollow::getFollowingId, userId));
    }

    private User userByUid(String uid) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPublicUid, uid));
        if (user == null) {
            throw new NoSuchElementException("用户不存在");
        }
        return user;
    }
}
