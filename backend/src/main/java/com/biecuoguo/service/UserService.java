package com.biecuoguo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.biecuoguo.domain.*;
import com.biecuoguo.dto.UserDtos;
import com.biecuoguo.dto.UserProfile;
import com.biecuoguo.mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.NoSuchElementException;

@Service
public class UserService {
    private final UserMapper userMapper;
    private final UserPreferenceMapper preferenceMapper;
    private final FavoriteMapper favoriteMapper;
    private final UserNoticeStatusMapper statusMapper;
    private final CommentMapper commentMapper;
    private final ReminderMapper reminderMapper;
    private final PostFavoriteMapper postFavoriteMapper;
    private final PostLikeMapper postLikeMapper;
    private final PostShareMapper postShareMapper;
    private final UserFollowMapper userFollowMapper;
    private final PostMapper postMapper;
    private final UserCheckInMapper checkInMapper;
    private final PresenceService presenceService;

    public UserService(UserMapper userMapper, UserPreferenceMapper preferenceMapper, FavoriteMapper favoriteMapper, UserNoticeStatusMapper statusMapper, CommentMapper commentMapper, ReminderMapper reminderMapper, PostFavoriteMapper postFavoriteMapper, PostLikeMapper postLikeMapper, PostShareMapper postShareMapper, UserFollowMapper userFollowMapper, PostMapper postMapper, UserCheckInMapper checkInMapper, PresenceService presenceService) {
        this.userMapper = userMapper;
        this.preferenceMapper = preferenceMapper;
        this.favoriteMapper = favoriteMapper;
        this.statusMapper = statusMapper;
        this.commentMapper = commentMapper;
        this.reminderMapper = reminderMapper;
        this.postFavoriteMapper = postFavoriteMapper;
        this.postLikeMapper = postLikeMapper;
        this.postShareMapper = postShareMapper;
        this.userFollowMapper = userFollowMapper;
        this.postMapper = postMapper;
        this.checkInMapper = checkInMapper;
        this.presenceService = presenceService;
    }

    public UserProfile profile(Long userId) {
        presenceService.markOnline(userId);
        User user = requireUser(userId);
        return UserProfile.from(user, preference(userId), presenceService.isOnline(userId));
    }

    @Transactional
    public UserProfile updateProfile(Long userId, UserDtos.UpdateProfileRequest request) {
        User user = requireUser(userId);
        if (request.nickname() != null && !request.nickname().isBlank()) user.setNickname(request.nickname());
        if (request.avatarUrl() != null) user.setAvatarUrl(request.avatarUrl());
        if (request.schoolId() != null) user.setSchoolId(request.schoolId());
        if (request.collegeId() != null) user.setCollegeId(request.collegeId());
        if (request.majorId() != null) user.setMajorId(request.majorId());
        if (request.college() != null) user.setCollege(request.college());
        if (request.major() != null) user.setMajor(request.major());
        if (request.grade() != null) user.setGrade(request.grade());
        if (request.bio() != null) user.setBio(request.bio());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        return UserProfile.from(user, preference(userId), presenceService.isOnline(userId));
    }

    @Transactional
    public UserProfile updatePreferences(Long userId, UserDtos.UpdatePreferencesRequest request) {
        User user = requireUser(userId);
        UserPreference preference = preference(userId);
        if (request.goalTags() != null) preference.setGoalTags(request.goalTags());
        if (request.interestCategories() != null) preference.setInterestCategories(request.interestCategories());
        if (request.preferUrgent() != null) preference.setPreferUrgent(request.preferUrgent());
        if (request.preferHighRisk() != null) preference.setPreferHighRisk(request.preferHighRisk());
        if (request.preferSameMajor() != null) preference.setPreferSameMajor(request.preferSameMajor());
        if (request.emailNotificationEnabled() != null) preference.setEmailNotificationEnabled(request.emailNotificationEnabled());
        if (request.browserNotificationEnabled() != null) preference.setBrowserNotificationEnabled(request.browserNotificationEnabled());
        if (request.remindBeforeHours() != null) preference.setRemindBeforeHours(request.remindBeforeHours());
        preference.setUpdatedAt(LocalDateTime.now());
        preferenceMapper.updateById(preference);
        return UserProfile.from(user, preference, presenceService.isOnline(userId));
    }

    public UserDtos.UserStats stats(Long userId) {
        long favorites = favoriteMapper.selectCount(new LambdaQueryWrapper<Favorite>().eq(Favorite::getUserId, userId));
        long completed = statusMapper.selectCount(new LambdaQueryWrapper<UserNoticeStatus>().eq(UserNoticeStatus::getUserId, userId).eq(UserNoticeStatus::getStatus, "completed"));
        long comments = commentMapper.selectCount(new LambdaQueryWrapper<Comment>().eq(Comment::getUserId, userId));
        long upcoming = reminderMapper.selectCount(new LambdaQueryWrapper<Reminder>().eq(Reminder::getUserId, userId).eq(Reminder::getStatus, "pending"));
        long likedPosts = postLikeMapper.selectCount(new LambdaQueryWrapper<PostLike>().eq(PostLike::getUserId, userId));
        long favoritePosts = postFavoriteMapper.selectCount(new LambdaQueryWrapper<PostFavorite>().eq(PostFavorite::getUserId, userId));
        long sharedPosts = postShareMapper.selectCount(new LambdaQueryWrapper<PostShare>().eq(PostShare::getUserId, userId));
        long following = userFollowMapper.selectCount(new LambdaQueryWrapper<UserFollow>().eq(UserFollow::getFollowerId, userId));
        long followers = userFollowMapper.selectCount(new LambdaQueryWrapper<UserFollow>().eq(UserFollow::getFollowingId, userId));
        long posts = postMapper.selectCount(new LambdaQueryWrapper<Post>().eq(Post::getAuthorId, userId).eq(Post::getStatus, "published"));
        return new UserDtos.UserStats(favorites + favoritePosts, likedPosts, sharedPosts, completed, comments, upcoming, completed, following, followers, posts);
    }

    public UserDtos.CheckInView checkInStatus(Long userId) {
        User user = requireUser(userId);
        LocalDate today = LocalDate.now();
        UserCheckIn todayRecord = findCheckIn(userId, today);
        if (todayRecord != null) {
            return checkInView(user, true, safe(todayRecord.getStreak()), 0, today);
        }
        UserCheckIn latest = latestCheckIn(userId);
        int currentStreak = latest != null && today.minusDays(1).equals(latest.getCheckinDate()) ? safe(latest.getStreak()) : 0;
        return checkInView(user, false, currentStreak, 0, today);
    }

    @Transactional
    public UserDtos.CheckInView checkIn(Long userId) {
        User user = requireUser(userId);
        LocalDate today = LocalDate.now();
        UserCheckIn existing = findCheckIn(userId, today);
        if (existing != null) {
            return checkInView(user, true, safe(existing.getStreak()), 0, today);
        }

        UserCheckIn latest = latestCheckIn(userId);
        int previousStreak = latest != null && today.minusDays(1).equals(latest.getCheckinDate()) ? safe(latest.getStreak()) : 0;
        int gained = checkInReward(user, previousStreak);
        int nextStreak = previousStreak + 1;
        applyXp(user, gained);

        UserCheckIn checkIn = new UserCheckIn();
        checkIn.setUserId(userId);
        checkIn.setCheckinDate(today);
        checkIn.setStreak(nextStreak);
        checkIn.setXpGained(gained);
        checkIn.setCreatedAt(LocalDateTime.now());
        checkInMapper.insert(checkIn);

        return checkInView(user, true, nextStreak, gained, today);
    }

    public UserDtos.PublicProfile publicProfile(String uid, Long viewerId) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPublicUid, uid));
        if (user == null) {
            throw new NoSuchElementException("用户不存在");
        }
        boolean mine = viewerId != null && viewerId.equals(user.getId());
        boolean following = viewerId != null && !mine && userFollowMapper.selectCount(new LambdaQueryWrapper<UserFollow>()
                .eq(UserFollow::getFollowerId, viewerId)
                .eq(UserFollow::getFollowingId, user.getId())) > 0;
        return new UserDtos.PublicProfile(UserProfile.from(user, preference(user.getId()), presenceService.isOnline(user.getId())), stats(user.getId()), following, mine);
    }

    private UserDtos.CheckInView checkInView(User user, boolean checkedInToday, int streak, int xpGained, java.time.LocalDate checkinDate) {
        return new UserDtos.CheckInView(UserProfile.from(user, preference(user.getId()), presenceService.isOnline(user.getId())), checkedInToday, streak, xpGained, checkinDate);
    }

    private UserCheckIn findCheckIn(Long userId, java.time.LocalDate date) {
        return checkInMapper.selectOne(new LambdaQueryWrapper<UserCheckIn>()
                .eq(UserCheckIn::getUserId, userId)
                .eq(UserCheckIn::getCheckinDate, date));
    }

    private UserCheckIn latestCheckIn(Long userId) {
        return checkInMapper.selectList(new LambdaQueryWrapper<UserCheckIn>()
                        .eq(UserCheckIn::getUserId, userId)
                        .orderByDesc(UserCheckIn::getCheckinDate))
                .stream()
                .findFirst()
                .orElse(null);
    }

    private int checkInReward(User user, int currentStreak) {
        return xpForLevel(user.getLevel() == null ? 1 : user.getLevel()) + Math.min(60, Math.max(0, currentStreak) * 3);
    }

    private void applyXp(User user, int amount) {
        int level = user.getLevel() == null ? 1 : user.getLevel();
        int xp = safe(user.getXp()) + amount;
        while (level < 100 && xp >= levelXpNeed(level)) {
            xp -= levelXpNeed(level);
            level += 1;
        }
        user.setLevel(level);
        user.setXp(xp);
        user.setLevelTitle(levelTitle(level));
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
    }

    private int xpForLevel(int level) {
        if (level <= 30) return 45;
        if (level <= 60) return 55;
        if (level <= 85) return 65;
        return 75;
    }

    private int totalXpForLevel(int level) {
        int completed = Math.max(0, level - 1);
        return Math.round(completed * completed * 28 + completed * 520);
    }

    private int levelXpNeed(int level) {
        return totalXpForLevel(level + 1) - totalXpForLevel(level);
    }

    private String levelTitle(int level) {
        return switch ((Math.max(1, Math.min(100, level)) - 1) / 10) {
            case 0 -> "萌新探路员";
            case 1 -> "早八幸存者";
            case 2 -> "选课赌神";
            case 3 -> "食堂测评官";
            case 4 -> "图书馆钉子户";
            case 5 -> "Deadline 驯服者";
            case 6 -> "校园情报员";
            case 7 -> "学分炼金术士";
            case 8 -> "毕设渡劫人";
            default -> "传说中的不鸽王";
        };
    }

    private int safe(Integer value) {
        return value == null ? 0 : value;
    }

    private User requireUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new NoSuchElementException("用户不存在");
        }
        return user;
    }

    private UserPreference preference(Long userId) {
        UserPreference preference = preferenceMapper.selectOne(new LambdaQueryWrapper<UserPreference>().eq(UserPreference::getUserId, userId));
        if (preference != null) {
            return preference;
        }
        LocalDateTime now = LocalDateTime.now();
        preference = new UserPreference();
        preference.setUserId(userId);
        preference.setGoalTags("");
        preference.setInterestCategories("");
        preference.setPreferUrgent(true);
        preference.setPreferHighRisk(true);
        preference.setPreferSameMajor(false);
        preference.setEmailNotificationEnabled(false);
        preference.setBrowserNotificationEnabled(true);
        preference.setRemindBeforeHours(24);
        preference.setCreatedAt(now);
        preference.setUpdatedAt(now);
        preferenceMapper.insert(preference);
        return preference;
    }
}
