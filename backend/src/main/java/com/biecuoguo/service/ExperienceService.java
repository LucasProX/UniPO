package com.biecuoguo.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.biecuoguo.config.AppTime;
import com.biecuoguo.domain.User;
import com.biecuoguo.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.NoSuchElementException;

@Service
public class ExperienceService {
    private final UserMapper userMapper;

    public ExperienceService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Transactional
    public User award(Long userId, int amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("经验值不能为负数");
        }
        User user = userMapper.lockById(userId);
        if (user == null) {
            throw new NoSuchElementException("用户不存在");
        }
        if (amount == 0) {
            return user;
        }

        Progress progress = progress(user.getLevel(), user.getXp(), amount);
        LocalDateTime now = AppTime.nowLocal();
        int updated = userMapper.update(null, new LambdaUpdateWrapper<User>().eq(User::getId, userId)
                .set(User::getXp, progress.xp())
                .set(User::getLevel, progress.level())
                .set(User::getLevelTitle, progress.title())
                .set(User::getUpdatedAt, now));
        if (updated != 1) {
            throw new NoSuchElementException("用户不存在");
        }

        user.setXp(progress.xp());
        user.setLevel(progress.level());
        user.setLevelTitle(progress.title());
        user.setUpdatedAt(now);
        return user;
    }

    /** 撤回一次奖励：按累计经验回退，可跨级下降，不会低于 0。 */
    @Transactional
    public User revoke(Long userId, int amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("经验值不能为负数");
        }
        User user = userMapper.lockById(userId);
        if (user == null) {
            throw new NoSuchElementException("用户不存在");
        }
        if (amount == 0) {
            return user;
        }
        Progress progress = reverted(user.getLevel(), user.getXp(), amount);
        LocalDateTime now = AppTime.nowLocal();
        userMapper.update(null, new LambdaUpdateWrapper<User>().eq(User::getId, userId)
                .set(User::getXp, progress.xp())
                .set(User::getLevel, progress.level())
                .set(User::getLevelTitle, progress.title())
                .set(User::getUpdatedAt, now));
        user.setXp(progress.xp());
        user.setLevel(progress.level());
        user.setLevelTitle(progress.title());
        user.setUpdatedAt(now);
        return user;
    }

    static Progress reverted(Integer currentLevel, Integer currentXp, int amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("经验值不能为负数");
        }
        int level = Math.max(1, Math.min(100, currentLevel == null ? 1 : currentLevel));
        long total = totalXpForLevel(level) + Math.max(0L, currentXp == null ? 0L : currentXp.longValue());
        long remaining = Math.max(0L, total - amount);
        int newLevel = 1;
        while (newLevel < 100 && remaining >= totalXpForLevel(newLevel + 1)) {
            newLevel++;
        }
        long xp = remaining - totalXpForLevel(newLevel);
        return new Progress(newLevel, (int) Math.min(Integer.MAX_VALUE, xp), levelTitle(newLevel));
    }

    static Progress progress(Integer currentLevel, Integer currentXp, int amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("经验值不能为负数");
        }
        int level = Math.max(1, Math.min(100, currentLevel == null ? 1 : currentLevel));
        long xp = Math.max(0L, currentXp == null ? 0L : currentXp.longValue()) + amount;
        while (level < 100 && xp >= xpNeededForNextLevel(level)) {
            xp -= xpNeededForNextLevel(level);
            level++;
        }
        if (xp > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("经验值超出范围");
        }
        return new Progress(level, (int) xp, levelTitle(level));
    }

    private static long xpNeededForNextLevel(int level) {
        return totalXpForLevel(level + 1) - totalXpForLevel(level);
    }

    private static long totalXpForLevel(int level) {
        long completed = Math.max(0, level - 1L);
        return completed * completed * 28 + completed * 520;
    }

    private static String levelTitle(int level) {
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

    record Progress(int level, int xp, String title) {}
}
