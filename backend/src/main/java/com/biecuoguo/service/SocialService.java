package com.biecuoguo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.biecuoguo.config.AppTime;
import com.biecuoguo.config.SearchText;
import com.biecuoguo.domain.User;
import com.biecuoguo.domain.UserBlock;
import com.biecuoguo.domain.UserFollow;
import com.biecuoguo.domain.UserReport;
import com.biecuoguo.dto.UserDtos;
import com.biecuoguo.mapper.UserBlockMapper;
import com.biecuoguo.mapper.UserFollowMapper;
import com.biecuoguo.mapper.UserMapper;
import com.biecuoguo.mapper.UserReportMapper;
import com.biecuoguo.mapper.SocialRelationshipLockMapper;
import com.biecuoguo.security.CurrentUser;
import com.biecuoguo.social.api.BlockQueryApi;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

@Service
public class SocialService implements BlockQueryApi {

    private static final int LIST_LIMIT = 200;
    private static final int USER_SEARCH_LIMIT = 20;
    private static final Set<String> REPORT_CATEGORIES = Set.of(
            "spam", "fraud", "harassment", "porn", "impersonation", "other"
    );

    private final UserMapper userMapper;
    private final UserFollowMapper followMapper;
    private final UserBlockMapper blockMapper;
    private final UserReportMapper reportMapper;
    private final SocialRelationshipLockMapper relationshipLockMapper;

    public SocialService(UserMapper userMapper, UserFollowMapper followMapper,
                         UserBlockMapper blockMapper, UserReportMapper reportMapper,
                         SocialRelationshipLockMapper relationshipLockMapper) {
        this.userMapper = userMapper;
        this.followMapper = followMapper;
        this.blockMapper = blockMapper;
        this.reportMapper = reportMapper;
        this.relationshipLockMapper = relationshipLockMapper;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean follow(String uid, CurrentUser currentUser) {
        User target = userByUid(uid);
        if (target.getId().equals(currentUser.id())) {
            throw new IllegalArgumentException("不能关注自己");
        }
        lockPair(currentUser.id(), target.getId());
        if (blockedEitherWayForUpdate(currentUser.id(), target.getId())) {
            throw new IllegalArgumentException("无法关注该用户");
        }
        UserFollow existing = followMapper.selectOne(new LambdaQueryWrapper<UserFollow>()
                .eq(UserFollow::getFollowerId, currentUser.id())
                .eq(UserFollow::getFollowingId, target.getId())
                .last("FOR UPDATE"));
        if (existing != null) {
            return true;
        }
        UserFollow follow = new UserFollow();
        follow.setFollowerId(currentUser.id());
        follow.setFollowingId(target.getId());
        follow.setCreatedAt(AppTime.nowLocal());
        followMapper.insert(follow);
        return true;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean unfollow(String uid, CurrentUser currentUser) {
        User target = userByUid(uid);
        if (!target.getId().equals(currentUser.id())) { lockPair(currentUser.id(), target.getId()); }
        followMapper.delete(new LambdaQueryWrapper<UserFollow>()
                .eq(UserFollow::getFollowerId, currentUser.id())
                .eq(UserFollow::getFollowingId, target.getId()));
        return true;
    }

    /** 粉丝列表：谁关注了 uid。 */
    public List<UserDtos.FollowUserView> followers(String uid, Long viewerId) {
        User target = userByUid(uid);
        List<UserFollow> rows = followMapper.selectList(new LambdaQueryWrapper<UserFollow>()
                .eq(UserFollow::getFollowingId, target.getId())
                .orderByDesc(UserFollow::getCreatedAt)
                .last("LIMIT " + LIST_LIMIT));
        return toViews(rows, true, viewerId);
    }

    /** 关注列表：uid 关注了谁。 */
    public List<UserDtos.FollowUserView> following(String uid, Long viewerId) {
        User target = userByUid(uid);
        List<UserFollow> rows = followMapper.selectList(new LambdaQueryWrapper<UserFollow>()
                .eq(UserFollow::getFollowerId, target.getId())
                .orderByDesc(UserFollow::getCreatedAt)
                .last("LIMIT " + LIST_LIMIT));
        return toViews(rows, false, viewerId);
    }

    /**
     * 移除粉丝：只有本人能移除自己的粉丝（把「对方关注我」这条边删掉）。
     * 不允许用这个接口去删别人对自己的关注关系。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean removeFollower(String followerUid, CurrentUser currentUser) {
        User follower = userByUid(followerUid);
        if (!follower.getId().equals(currentUser.id())) { lockPair(currentUser.id(), follower.getId()); }
        followMapper.delete(new LambdaQueryWrapper<UserFollow>()
                .eq(UserFollow::getFollowerId, follower.getId())
                .eq(UserFollow::getFollowingId, currentUser.id()));
        return true;
    }

    /** 上次查看粉丝列表之后新增的粉丝数；从未看过返回 0（首次进入不补历史）。 */
    public long newFollowerCount(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null || user.getFollowersSeenAt() == null) {
            return 0;
        }
        return followMapper.selectCount(new LambdaQueryWrapper<UserFollow>()
                .eq(UserFollow::getFollowingId, userId)
                .gt(UserFollow::getCreatedAt, user.getFollowersSeenAt()));
    }

    @Transactional
    public void markFollowersSeen(Long userId) {
        userMapper.update(null, new LambdaUpdateWrapper<User>().eq(User::getId, userId)
                .set(User::getFollowersSeenAt, AppTime.nowLocal()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UserDtos.BlockStatusView block(String uid, CurrentUser currentUser) {
        User target = userByUid(uid);
        if (target.getId().equals(currentUser.id())) {
            throw new IllegalArgumentException("不能拉黑自己");
        }
        lockPair(currentUser.id(), target.getId());
        boolean exists = blockMapper.selectOne(new LambdaQueryWrapper<UserBlock>()
                .eq(UserBlock::getBlockerId, currentUser.id())
                .eq(UserBlock::getBlockedId, target.getId())
                .last("FOR UPDATE")) != null;
        if (!exists) {
            UserBlock block = new UserBlock();
            block.setBlockerId(currentUser.id());
            block.setBlockedId(target.getId());
            block.setCreatedAt(AppTime.nowLocal());
            blockMapper.insert(block);
        }
        // 拉黑时双向取关：拉黑后还互相关注是最容易让人困惑的状态。
        followMapper.delete(new LambdaQueryWrapper<UserFollow>()
                .eq(UserFollow::getFollowerId, currentUser.id())
                .eq(UserFollow::getFollowingId, target.getId()));
        followMapper.delete(new LambdaQueryWrapper<UserFollow>()
                .eq(UserFollow::getFollowerId, target.getId())
                .eq(UserFollow::getFollowingId, currentUser.id()));
        return new UserDtos.BlockStatusView(true, blockedForUpdate(target.getId(), currentUser.id()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UserDtos.BlockStatusView unblock(String uid, CurrentUser currentUser) {
        User target = userByUid(uid);
        if (!target.getId().equals(currentUser.id())) { lockPair(currentUser.id(), target.getId()); }
        blockMapper.delete(new LambdaQueryWrapper<UserBlock>()
                .eq(UserBlock::getBlockerId, currentUser.id())
                .eq(UserBlock::getBlockedId, target.getId()));
        return new UserDtos.BlockStatusView(false, blockedForUpdate(target.getId(), currentUser.id()));
    }

    public UserDtos.BlockStatusView blockStatus(Long viewerId, Long targetId) {
        if (viewerId == null) return new UserDtos.BlockStatusView(false, false);
        return new UserDtos.BlockStatusView(
                blocked(viewerId, targetId),
                blockedBy(targetId, viewerId)
        );
    }

    @Transactional
    public Long report(String uid, UserDtos.ReportRequest request, CurrentUser currentUser) {
        User target = userByUid(uid);
        if (target.getId().equals(currentUser.id())) {
            throw new IllegalArgumentException("不能举报自己");
        }
        String category = request.category() == null ? "" : request.category().trim();
        if (!REPORT_CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("举报分类不合法");
        }
        String detail = request.detail() == null ? null : request.detail().trim();
        if (detail != null && detail.length() > 500) {
            throw new IllegalArgumentException("补充说明最多 500 字");
        }
        UserReport report = new UserReport();
        report.setReporterId(currentUser.id());
        report.setTargetUserId(target.getId());
        report.setCategory(category);
        report.setDetail(detail == null || detail.isBlank() ? null : detail);
        report.setStatus("pending");
        report.setCreatedAt(AppTime.nowLocal());
        reportMapper.insert(report);
        return report.getId();
    }

    public long followingCount(Long userId) {
        return followMapper.selectCount(new LambdaQueryWrapper<UserFollow>().eq(UserFollow::getFollowerId, userId));
    }

    public long followerCount(Long userId) {
        return followMapper.selectCount(new LambdaQueryWrapper<UserFollow>().eq(UserFollow::getFollowingId, userId));
    }

    /**
     * 按昵称或 UID 找人：UID 完全相同排最前，其余按昵称全文匹配。
     * 只返回正常状态的账号，双方任一拉黑的不出现，自己也不出现。
     */
    public List<UserDtos.FollowUserView> searchUsers(String query, CurrentUser currentUser) {
        if (currentUser == null || currentUser.id() == null) {
            throw new org.springframework.security.authentication.AuthenticationCredentialsNotFoundException("请先登录");
        }
        String text = SearchText.normalize(query);
        Set<Long> hidden = new HashSet<>(blockedUserIds(currentUser.id()));
        hidden.add(currentUser.id());
        List<User> matches = new ArrayList<>();
        User exact = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getPublicUid, text).eq(User::getStatus, "active"));
        if (exact != null && !hidden.contains(exact.getId())) matches.add(exact);
        userMapper.selectList(new LambdaQueryWrapper<User>()
                        .eq(User::getStatus, "active")
                        .apply("MATCH(nickname) AGAINST ({0} IN BOOLEAN MODE)", "\"" + text + "\"")
                        .notIn(!hidden.isEmpty(), User::getId, hidden)
                        .orderByDesc(User::getLevel).orderByAsc(User::getId)
                        .last("LIMIT " + USER_SEARCH_LIMIT))
                .forEach(user -> {
                    if (matches.size() < USER_SEARCH_LIMIT && matches.stream().noneMatch(m -> m.getId().equals(user.getId()))) {
                        matches.add(user);
                    }
                });
        List<UserFollow> rows = matches.stream().map(user -> {
            UserFollow row = new UserFollow();
            row.setFollowerId(currentUser.id());
            row.setFollowingId(user.getId());
            return row;
        }).toList();
        return toViews(rows, false, currentUser.id());
    }

    private List<UserDtos.FollowUserView> toViews(List<UserFollow> rows, boolean followers, Long viewerId) {
        if (rows.isEmpty()) return List.of();
        List<Long> userIds = rows.stream()
                .map(follow -> followers ? follow.getFollowerId() : follow.getFollowingId())
                .toList();
        Map<Long, User> users = new LinkedHashMap<>();
        for (User user : userMapper.selectBatchIds(userIds)) {
            users.put(user.getId(), user);
        }
        Set<Long> viewerFollowing = new HashSet<>();
        if (viewerId != null) {
            followMapper.selectList(new LambdaQueryWrapper<UserFollow>()
                            .eq(UserFollow::getFollowerId, viewerId)
                            .in(UserFollow::getFollowingId, userIds))
                    .forEach(follow -> viewerFollowing.add(follow.getFollowingId()));
        }
        List<UserDtos.FollowUserView> result = new ArrayList<>();
        for (UserFollow row : rows) {
            Long userId = followers ? row.getFollowerId() : row.getFollowingId();
            User user = users.get(userId);
            if (user == null) continue;
            result.add(new UserDtos.FollowUserView(
                    user.getId(),
                    user.getPublicUid(),
                    user.getNickname(),
                    user.getAvatarUrl(),
                    user.getRole(),
                    user.getLevel() == null ? 1 : user.getLevel(),
                    user.getLevelTitle(),
                    user.getCollege(),
                    user.getMajor(),
                    user.getGrade(),
                    viewerFollowing.contains(userId),
                    AppTime.toOffset(row.getCreatedAt())
            ));
        }
        return result;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean isBlockedEitherWay(Long a, Long b) {
        if (a == null || b == null || a.equals(b)) return false;
        return blockMapper.existsEitherWay(a, b);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean isBlockedInLockedPair(Long a, Long b) {
        requireRelationshipTransaction(a, b);
        return blockMapper.existsEitherWayForShare(a, b);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Set<Long> blockedUserIds(Long viewerId) {
        if (viewerId == null) return Set.of();
        return Set.copyOf(blockMapper.selectBlockedUserIds(viewerId));
    }

    private boolean blocked(Long blockerId, Long blockedId) {
        return blockMapper.selectCount(new LambdaQueryWrapper<UserBlock>()
                .eq(UserBlock::getBlockerId, blockerId)
                .eq(UserBlock::getBlockedId, blockedId)) > 0;
    }

    private boolean blockedBy(Long targetId, Long viewerId) {
        return blocked(targetId, viewerId);
    }

    private void lockRelationshipPair(Long firstUserId, Long secondUserId) {
        long low = Math.min(firstUserId, secondUserId);
        long high = Math.max(firstUserId, secondUserId);
        // Always acquire participant locks before the pair lock. Account deletion holds the user's
        // exclusive lock through relationship cleanup; a late writer must observe deleted and stop.
        if (!"active".equals(userMapper.lockStatusForShare(low))
                || !"active".equals(userMapper.lockStatusForShare(high))) {
            throw new NoSuchElementException("用户不存在或已注销");
        }
        relationshipLockMapper.ensurePair(low, high);
        if (relationshipLockMapper.lockPair(low, high) == null) {
            throw new IllegalStateException("无法锁定用户关系");
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockPair(Long firstUserId, Long secondUserId) {
        requireRelationshipTransaction(firstUserId, secondUserId);
        lockRelationshipPair(firstUserId, secondUserId);
    }

    private void requireRelationshipTransaction(Long firstUserId, Long secondUserId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("用户关系锁需要数据库事务");
        }
        if (firstUserId == null || secondUserId == null || firstUserId.equals(secondUserId)) {
            throw new IllegalArgumentException("用户关系锁参数不合法");
        }
    }

    private boolean blockedEitherWayForUpdate(Long a, Long b) {
        return blockedForUpdate(a, b) || blockedForUpdate(b, a);
    }

    private boolean blockedForUpdate(Long blockerId, Long blockedId) {
        return blockMapper.selectOne(new LambdaQueryWrapper<UserBlock>()
                .eq(UserBlock::getBlockerId, blockerId)
                .eq(UserBlock::getBlockedId, blockedId)
                .last("FOR UPDATE")) != null;
    }

    private User userByUid(String uid) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPublicUid, uid));
        if (user == null) {
            throw new NoSuchElementException("用户不存在");
        }
        return user;
    }
}
