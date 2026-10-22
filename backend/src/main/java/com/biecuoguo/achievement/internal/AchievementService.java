package com.biecuoguo.achievement.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.biecuoguo.achievement.api.AchievementDtos;
import com.biecuoguo.achievement.api.GrowthTerms;
import com.biecuoguo.config.AppTime;
import com.biecuoguo.domain.User;
import com.biecuoguo.mapper.UserMapper;
import com.biecuoguo.service.ExperienceService;
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
    private final ExperienceService experienceService;

    public AchievementService(AchievementStageMapper stageMapper, AchievementTaskMapper taskMapper,
                              AchievementProgressMapper progressMapper, BadgeMapper badgeMapper,
                              UserBadgeMapper userBadgeMapper, UserMapper userMapper,
                              ExperienceService experienceService) {
        this.stageMapper = stageMapper;
        this.taskMapper = taskMapper;
        this.progressMapper = progressMapper;
        this.badgeMapper = badgeMapper;
        this.userBadgeMapper = userBadgeMapper;
        this.userMapper = userMapper;
        this.experienceService = experienceService;
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
        Integer termIndex = user.getEntryYear() == null ? null
                : GrowthTerms.termIndex(user.getEntryYear(), AppTime.nowLocal().toLocalDate());
        String currentStageCode = termIndex == null ? null : stageForTerm(stages, termIndex);
        String firstUnfinished = null;
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
            if (firstUnfinished == null && stageDone < stageTotal) {
                firstUnfinished = stage.getCode();
            }
            stageViews.add(new AchievementDtos.StageView(
                    stage.getId(), stage.getCode(), stage.getTitle(), stage.getSubtitle(),
                    stage.getStageOrder() == null ? 0 : stage.getStageOrder(),
                    Boolean.TRUE.equals(stage.getBoss()),
                    taskViews, stageDone, stageTotal
            ));
        }
        if (currentStageCode == null) {
            currentStageCode = firstUnfinished;
        }
        if (currentStageCode == null && !stages.isEmpty()) {
            currentStageCode = stages.get(stages.size() - 1).getCode();
        }
        AchievementDtos.TaskView nextTask = null;
        for (AchievementDtos.StageView stage : stageViews) {
            if (!stage.code().equals(currentStageCode)) continue;
            nextTask = stage.tasks().stream().filter(task -> !task.completed()).findFirst().orElse(null);
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
                done, total, currentStageCode, stageViews, badgeViews,
                termIndex == null ? "PROGRESS" : "CALENDAR",
                user.getEntryYear(),
                termIndex == null ? null : GrowthTerms.termLabel(termIndex),
                nextTask
        );
    }

    /** 入学前一律落在第一个阶段；超过所有区间的落在最后一个阶段。 */
    static String stageForTerm(List<AchievementStage> stages, int termIndex) {
        if (stages.isEmpty()) return null;
        if (termIndex < 0) return stages.get(0).getCode();
        for (AchievementStage stage : stages) {
            if (stage.getTermFrom() == null) continue;
            boolean afterStart = termIndex >= stage.getTermFrom();
            boolean beforeEnd = stage.getTermTo() == null || termIndex <= stage.getTermTo();
            if (afterStart && beforeEnd) return stage.getCode();
        }
        return stages.get(stages.size() - 1).getCode();
    }

    @Transactional
    public AchievementDtos.AchievementView complete(Long taskId, Long userId) {
        AchievementTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new NoSuchElementException("任务不存在");
        }
        // 完成与撤回都先锁用户行，串行同一用户的进度、经验和徽章变化。
        if (userMapper.lockById(userId) == null) {
            throw new NoSuchElementException("用户不存在");
        }
        Long exists = progressMapper.selectCount(new LambdaQueryWrapper<AchievementProgress>()
                .eq(AchievementProgress::getUserId, userId)
                .eq(AchievementProgress::getTaskId, taskId));
        if (exists == 0) {
            AchievementProgress progress = new AchievementProgress();
            progress.setUserId(userId);
            progress.setTaskId(taskId);
            progress.setCompletedAt(AppTime.nowLocal());
            progressMapper.insert(progress);

            experienceService.award(userId, task.getXp() == null ? 0 : task.getXp());
            awardBadge(userId, task.getBadgeCode());
        }
        return view(userId);
    }

    /** 撤回自报完成：删除进度，按任务经验回退，并收回只由该任务授予的徽章。重复撤回无副作用。 */
    @Transactional
    public AchievementDtos.AchievementView undo(Long taskId, Long userId) {
        AchievementTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new NoSuchElementException("任务不存在");
        }
        if (userMapper.lockById(userId) == null) {
            throw new NoSuchElementException("用户不存在");
        }
        int removed = progressMapper.delete(new LambdaQueryWrapper<AchievementProgress>()
                .eq(AchievementProgress::getUserId, userId)
                .eq(AchievementProgress::getTaskId, taskId));
        if (removed > 0) {
            experienceService.revoke(userId, task.getXp() == null ? 0 : task.getXp());
            revokeBadge(userId, task);
        }
        return view(userId);
    }

    private void revokeBadge(Long userId, AchievementTask task) {
        String badgeCode = task.getBadgeCode();
        if (badgeCode == null || badgeCode.isBlank()) return;
        List<Long> otherGrantingTasks = taskMapper.selectList(new LambdaQueryWrapper<AchievementTask>()
                        .eq(AchievementTask::getBadgeCode, badgeCode)
                        .ne(AchievementTask::getId, task.getId()))
                .stream().map(AchievementTask::getId).toList();
        if (!otherGrantingTasks.isEmpty() && progressMapper.selectCount(new LambdaQueryWrapper<AchievementProgress>()
                .eq(AchievementProgress::getUserId, userId)
                .in(AchievementProgress::getTaskId, otherGrantingTasks)) > 0) {
            return;
        }
        userBadgeMapper.delete(new LambdaQueryWrapper<UserBadge>()
                .eq(UserBadge::getUserId, userId)
                .eq(UserBadge::getBadgeCode, badgeCode));
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
        userBadge.setEarnedAt(AppTime.nowLocal());
        userBadgeMapper.insert(userBadge);
    }
}
