package com.biecuoguo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.biecuoguo.domain.Conversation;
import com.biecuoguo.domain.Message;
import com.biecuoguo.domain.User;
import com.biecuoguo.domain.UserFollow;
import com.biecuoguo.mapper.ConversationMapper;
import com.biecuoguo.mapper.MessageMapper;
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
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;

    public SocialService(UserMapper userMapper, UserFollowMapper followMapper, ConversationMapper conversationMapper, MessageMapper messageMapper) {
        this.userMapper = userMapper;
        this.followMapper = followMapper;
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
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
        LocalDateTime now = LocalDateTime.now();
        follow.setCreatedAt(now);
        followMapper.insert(follow);
        createFollowNotice(currentUser.id(), target.getId(), now);
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

    private void createFollowNotice(Long followerId, Long targetId, LocalDateTime now) {
        Conversation conversation = findOrCreate(targetId, followerId, now);
        User follower = userMapper.selectById(followerId);
        Message message = new Message();
        message.setConversationId(conversation.getId());
        message.setSenderId(followerId);
        message.setReceiverId(targetId);
        message.setContent((follower == null ? "有同学" : follower.getNickname()) + " 关注了你");
        message.setRead(false);
        message.setCreatedAt(now);
        messageMapper.insert(message);
        conversation.setLastMessageAt(now);
        conversation.setUpdatedAt(now);
        conversationMapper.updateById(conversation);
    }

    private Conversation findOrCreate(Long userId, Long peerId, LocalDateTime now) {
        Long one = Math.min(userId, peerId);
        Long two = Math.max(userId, peerId);
        Conversation existing = conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserOneId, one)
                .eq(Conversation::getUserTwoId, two));
        if (existing != null) {
            return existing;
        }
        Conversation conversation = new Conversation();
        conversation.setUserOneId(one);
        conversation.setUserTwoId(two);
        conversation.setLastMessageAt(now);
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        conversationMapper.insert(conversation);
        return conversation;
    }
}
