package com.biecuoguo.achievement.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.biecuoguo.achievement.api.AchievementDtos;
import com.biecuoguo.config.AppTime;
import com.biecuoguo.domain.User;
import com.biecuoguo.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
public class AchievementService {

    private final AchievementStageMapper stageMapper;
    private final AchievementTaskMapper taskMapper;
    private final AchievementProgressMapper progressMapper;
    private final BadgeMapper badgeMapper;
    private final UserBadgeMapper userBadgeMapper;
    private final UserMapper userMapper;

    public AchievementService(AchievementStageMapper stageMapper, AchievementTaskMapper taskMapper,
                              AchievementProgressMapper progressMapper, BadgeMapper badgeMapper,
                              UserBadgeMapper userBadgeMapper, UserMapper userMapper) {
        this.stageMapper = stageMapper;
        this.taskMapper = taskMapper;
        this.progressMapper = progressMapper;
        this.badgeMapper = badgeMapper;
        this.userBadgeMapper = userBadgeMapper;
        this.userMapper = userMapper;
    }

    public AchievementDtos.AchievementView view(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new NoSuchElementException("用户不存在");
        }

        List<AchievementStage> stages = stageMapper.selectList(new LambdaQueryWrapper<AchievementStage>()
                .orderByAsc(AchievementStage::getStageOrder));
        List<AchievementTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<AchievementTask>()
                .orderByAsc(AchievementTask::getStageId)
                .orderByAsc(AchievementTask::getTaskOrder));

        Map<Long, LocalDateTime> completed = new HashMap<>();
        for (AchievementProgress progress : progressMapper.selectList(new LambdaQueryWrapper<AchievementProgress>()
                .eq(AchievementProgress::getUserId, userId))) {
            completed.put(progress.getTaskId(), progress.getCompletedAt());
        }

        Map<String, LocalDateTime> earned = new HashMap<>();
        for (UserBadge badge : userBadgeMapper.selectList(new LambdaQueryWrapper<UserBadge>()
                .eq(UserBadge::getUserId, userId))) {
            earned.put(badge.getBadgeCode(), badge.getEarnedAt());
        }

        List<AchievementDtos.StageView> stageViews = new ArrayList<>();
        int total = 0;
        int done = 0;
        String currentStageCode = null;
        for (AchievementStage stage : stages) {
            List<AchievementDtos.TaskView> taskViews = new ArrayList<>();
            int stageDone = 0;
            int stageTotal = 0;
            for (AchievementTask task : tasks) {
                if (!stage.getId().equals(task.getStageId())) continue;
                stageTotal++;
                total++;
                LocalDateTime at = completed.get(task.getId());
                boolean isDone = at != null;
                if (isDone) {
                    stageDone++;
                    done++;
                }
                taskViews.add(new AchievementDtos.TaskView(
                        task.getId(),
                        task.getTitle(),
                        task.getDescription(),
                        task.getXp() == null ? 0 : task.getXp(),
                        isDone,
                        at == null ? null : AppTime.toOffset(at).toString()
                ));
            }
            if (currentStageCode == null && stageDone < stageTotal) {
                currentStageCode = stage.getCode();
            }
            stageViews.add(new AchievementDtos.StageView(
                    stage.getId(), stage.getCode(), stage.getTitle(), stage.getSubtitle(),
                    stage.getStageOrder() == null ? 0 : stage.getStageOrder(),
                    Boolean.TRUE.equals(stage.getBoss()),
                    taskViews, stageDone, stageTotal
            ));
        }
        if (currentStageCode == null && !stages.isEmpty()) {
            currentStageCode = stages.get(stages.size() - 1).getCode();
        }

        List<AchievementDtos.BadgeView> badgeViews = new ArrayList<>();
        for (Badge badge : badgeMapper.selectList(new LambdaQueryWrapper<Badge>()
                .orderByAsc(Badge::getSortOrder))) {
            LocalDateTime at = earned.get(badge.getCode());
            badgeViews.add(new AchievementDtos.BadgeView(
                    badge.getCode(), badge.getTitle(), badge.getDescription(), badge.getRarity(),
                    badge.getIcon(), at != null, at == null ? null : AppTime.toOffset(at).toString()
            ));
        }

        return new AchievementDtos.AchievementView(
                user.getLevel() == null ? 1 : user.getLevel(),
                user.getXp() == null ? 0 : user.getXp(),
                done, total, currentStageCode, stageViews, badgeViews
        );
    }

    @Transactional
    public AchievementDtos.AchievementView complete(Long taskId, Long userId) {
        AchievementTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new NoSuchElementException("任务不存在");
        }
        Long exists = progressMapper.selectCount(new LambdaQueryWrapper<AchievementProgress>()
                .eq(AchievementProgress::getUserId, userId)
                .eq(AchievementProgress::getTaskId, taskId));
        if (exists == 0) {
            AchievementProgress progress = new AchievementProgress();
            progress.setUserId(userId);
            progress.setTaskId(taskId);
            progress.setCompletedAt(LocalDateTime.now());
            progressMapper.insert(progress);

            User user = userMapper.selectById(userId);
            if (user != null) {
                user.setXp((user.getXp() == null ? 0 : user.getXp()) + (task.getXp() == null ? 0 : task.getXp()));
                userMapper.updateById(user);
            }
            awardBadge(userId, task.getBadgeCode());
        }
        return view(userId);
    }

    private void awardBadge(Long userId, String badgeCode) {
        if (badgeCode == null || badgeCode.isBlank()) return;
        Long exists = userBadgeMapper.selectCount(new LambdaQueryWrapper<UserBadge>()
                .eq(UserBadge::getUserId, userId)
                .eq(UserBadge::getBadgeCode, badgeCode));
        if (exists > 0) return;
        if (badgeMapper.selectById(badgeCode) == null) return;
        UserBadge userBadge = new UserBadge();
        userBadge.setUserId(userId);
        userBadge.setBadgeCode(badgeCode);
        userBadge.setEarnedAt(LocalDateTime.now());
        userBadgeMapper.insert(userBadge);
    }
}
