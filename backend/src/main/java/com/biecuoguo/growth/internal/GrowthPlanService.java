package com.biecuoguo.growth.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.biecuoguo.achievement.api.AchievementDtos;
import com.biecuoguo.achievement.api.AchievementQueryApi;
import com.biecuoguo.achievement.api.GrowthTerms;
import com.biecuoguo.activity.api.ActivityParticipationApi;
import com.biecuoguo.config.AppTime;
import com.biecuoguo.domain.User;
import com.biecuoguo.error.StateConflictException;
import com.biecuoguo.growth.api.GrowthDtos;
import com.biecuoguo.mapper.UserMapper;
import com.biecuoguo.social.api.BlockQueryApi;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeSet;

/**
 * 成长整合（A4）：把活动、主线任务和个人任务按学期组织起来。
 *
 * <p>学期用起始日（9 月 1 日 / 2 月 1 日）标识，没有入学年份也能用；有入学年份时额外给出
 * 「大一上学期」这类标签和对应的主线阶段。个人任务只记完成状态，不发经验和金币——
 * 有奖励的事情只能来自有等级的组织者发布的活动。
 */
@Service
public class GrowthPlanService {
    static final int MAX_TASKS_PER_TERM = 50;
    /** 没有入学年份时最多往前翻的学期数。 */
    static final int HISTORY_TERMS_WITHOUT_ENTRY_YEAR = 8;
    private static final int ARCHIVE_ACTIVITY_LIMIT = 500;

    private final UserTaskMapper taskMapper;
    private final UserMapper userMapper;
    private final AchievementQueryApi achievements;
    private final ActivityParticipationApi participation;
    private final BlockQueryApi blockQueryApi;

    public GrowthPlanService(UserTaskMapper taskMapper, UserMapper userMapper, AchievementQueryApi achievements,
                             ActivityParticipationApi participation, BlockQueryApi blockQueryApi) {
        this.blockQueryApi = blockQueryApi;
        this.taskMapper = taskMapper;
        this.userMapper = userMapper;
        this.achievements = achievements;
        this.participation = participation;
    }

    // ---- 本学期清单 ----

    @Transactional(readOnly = true)
    public GrowthDtos.TermPlanView termPlan(long userId, LocalDate requestedStart) {
        User user = user(userId);
        LocalDate current = GrowthTerms.termStartOf(AppTime.nowLocal().toLocalDate());
        LocalDate start = requestedStart == null ? current : requestedStart;
        if (!GrowthTerms.isTermStart(start)) throw new IllegalArgumentException("学期起始日只能是 9 月 1 日或 2 月 1 日");
        LocalDate earliest = earliestTerm(user, current);
        LocalDate latest = GrowthTerms.nextTermStart(current);
        if (start.isBefore(earliest) || start.isAfter(latest)) throw new IllegalArgumentException("超出可查看的学期范围");
        LocalDate next = GrowthTerms.nextTermStart(start);

        List<ActivityParticipationApi.Participation> activities =
                participation.participations(userId, start.atStartOfDay(), next.atStartOfDay());
        Integer termIndex = user.getEntryYear() == null ? null : GrowthTerms.termIndex(user.getEntryYear(), start);
        AchievementDtos.StageView stage = officialStage(userId, termIndex, start.equals(current));
        List<GrowthDtos.PersonalTaskView> tasks = taskMapper.selectList(new LambdaQueryWrapper<UserTask>()
                        .eq(UserTask::getUserId, userId).eq(UserTask::getTermStart, start)
                        .orderByAsc(UserTask::getCompletedAt).orderByAsc(UserTask::getId))
                .stream().map(GrowthPlanService::view)
                // 未完成在前（按截止时间，没有截止的排后），已完成在后
                .sorted(Comparator.comparing(GrowthDtos.PersonalTaskView::completed)
                        .thenComparing(task -> task.dueAt() == null ? OffsetDateTime.MAX : task.dueAt())
                        .thenComparingLong(GrowthDtos.PersonalTaskView::id))
                .toList();

        int done = 0;
        int total = 0;
        int xp = 0;
        int coins = 0;
        int attended = 0;
        for (ActivityParticipationApi.Participation item : activities) {
            if (item.checkedIn()) {
                attended++;
                xp += item.xpGranted();
                coins += item.coinsGranted();
            }
            if (!countsTowardPlan(item)) continue;
            total++;
            if (item.checkedIn()) done++;
        }
        if (stage != null) {
            total += stage.totalCount();
            done += stage.completedCount();
        }
        for (GrowthDtos.PersonalTaskView task : tasks) {
            total++;
            if (task.completed()) done++;
        }
        return new GrowthDtos.TermPlanView(
                start, next.minusDays(1), GrowthTerms.termLabel(user.getEntryYear(), start), termIndex,
                start.equals(current),
                start.isAfter(earliest) ? GrowthTerms.previousTermStart(start) : null,
                start.isBefore(latest) ? next : null,
                canAddTo(start, current),
                new GrowthDtos.TermSummary(done, total, attended, xp, coins),
                activities, stage, tasks);
    }

    /** 被拒绝的报名和已取消的活动仍展示，但不算进完成度。 */
    static boolean countsTowardPlan(ActivityParticipationApi.Participation item) {
        if (item.checkedIn()) return true;
        return "PUBLISHED".equals(item.activityStatus()) && !"REJECTED".equals(item.registrationStatus())
                && !item.checkinRevoked();
    }

    private AchievementDtos.StageView officialStage(long userId, Integer termIndex, boolean current) {
        if (termIndex != null) return achievements.stageForTerm(userId, termIndex);
        if (!current) return null;
        AchievementDtos.AchievementView view = achievements.view(userId);
        return view.stages().stream().filter(stage -> stage.code().equals(view.currentStageCode()))
                .findFirst().orElse(null);
    }

    static LocalDate earliestTerm(User user, LocalDate current) {
        if (user.getEntryYear() != null) {
            LocalDate entry = LocalDate.of(user.getEntryYear(), 9, 1);
            return entry.isAfter(current) ? current : entry;
        }
        LocalDate earliest = current;
        for (int i = 0; i < HISTORY_TERMS_WITHOUT_ENTRY_YEAR; i++) earliest = GrowthTerms.previousTermStart(earliest);
        return earliest;
    }

    static boolean canAddTo(LocalDate termStart, LocalDate current) {
        return termStart.equals(current) || termStart.equals(GrowthTerms.nextTermStart(current));
    }

    // ---- 个人任务 ----

    @Transactional
    public GrowthDtos.PersonalTaskView create(long userId, GrowthDtos.PersonalTaskRequest request) {
        // 锁用户行串行同一人的新增，学期上限因此不会被并发突破。
        if (userMapper.lockById(userId) == null) throw new NoSuchElementException("用户不存在");
        LocalDate current = GrowthTerms.termStartOf(AppTime.nowLocal().toLocalDate());
        LocalDate start = request.termStart() == null ? current : request.termStart();
        if (!GrowthTerms.isTermStart(start)) throw new IllegalArgumentException("学期起始日只能是 9 月 1 日或 2 月 1 日");
        if (!canAddTo(start, current)) throw new StateConflictException("只能给当前或下一个学期添加任务");
        Long count = taskMapper.selectCount(new LambdaQueryWrapper<UserTask>()
                .eq(UserTask::getUserId, userId).eq(UserTask::getTermStart, start));
        if (count >= MAX_TASKS_PER_TERM) throw new StateConflictException("每个学期最多 " + MAX_TASKS_PER_TERM + " 个自定义任务");
        LocalDateTime now = now();
        UserTask task = new UserTask();
        task.setUserId(userId);
        task.setTermStart(start);
        task.setTitle(title(request.title()));
        task.setNote(note(request.note()));
        task.setDueAt(request.dueAt() == null ? null : AppTime.toLocal(request.dueAt()));
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        taskMapper.insert(task);
        return view(task);
    }

    @Transactional
    public GrowthDtos.PersonalTaskView update(long userId, long taskId, GrowthDtos.PersonalTaskRequest request) {
        UserTask task = own(userId, taskId);
        task.setTitle(title(request.title()));
        task.setNote(note(request.note()));
        task.setDueAt(request.dueAt() == null ? null : AppTime.toLocal(request.dueAt()));
        task.setUpdatedAt(now());
        taskMapper.updateById(task);
        return view(task);
    }

    /** 完成与撤回都是幂等的：重复调用不改变首次完成时间。 */
    @Transactional
    public GrowthDtos.PersonalTaskView setCompleted(long userId, long taskId, boolean completed) {
        UserTask task = own(userId, taskId);
        if (completed == (task.getCompletedAt() != null)) return view(task);
        LocalDateTime now = now();
        task.setCompletedAt(completed ? now : null);
        task.setUpdatedAt(now);
        taskMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<UserTask>()
                .eq(UserTask::getId, taskId).eq(UserTask::getUserId, userId)
                .set(UserTask::getCompletedAt, task.getCompletedAt())
                .set(UserTask::getUpdatedAt, now));
        return view(task);
    }

    /** 返回被删除前的任务，客户端用它确认删掉的是哪一条。 */
    @Transactional
    public GrowthDtos.PersonalTaskView delete(long userId, long taskId) {
        UserTask task = own(userId, taskId);
        taskMapper.delete(new LambdaQueryWrapper<UserTask>().eq(UserTask::getId, taskId).eq(UserTask::getUserId, userId));
        return view(task);
    }

    private UserTask own(long userId, long taskId) {
        UserTask task = taskMapper.selectOne(new LambdaQueryWrapper<UserTask>()
                .eq(UserTask::getId, taskId).eq(UserTask::getUserId, userId).last("FOR UPDATE"));
        // 别人的任务与不存在的任务同样返回 404，不暴露 ID 是否存在。
        if (task == null) throw new NoSuchElementException("任务不存在");
        return task;
    }

    private static String title(String raw) {
        String title = raw == null ? "" : raw.trim();
        if (title.isEmpty()) throw new IllegalArgumentException("任务标题不能为空");
        if (title.length() > 40) throw new IllegalArgumentException("任务标题最多 40 个字");
        return title;
    }

    private static String note(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String note = raw.trim();
        if (note.length() > 200) throw new IllegalArgumentException("备注最多 200 个字");
        return note;
    }

    static GrowthDtos.PersonalTaskView view(UserTask task) {
        return new GrowthDtos.PersonalTaskView(
                task.getId(), task.getTermStart(), task.getTitle(), task.getNote(),
                task.getDueAt() == null ? null : AppTime.toOffset(task.getDueAt()),
                task.getCompletedAt() != null,
                task.getCompletedAt() == null ? null : AppTime.toOffset(task.getCompletedAt()),
                AppTime.toOffset(task.getCreatedAt()));
    }

    // ---- 成长档案 ----

    @Transactional(readOnly = true)
    public GrowthDtos.ArchiveView archive(long userId) {
        User user = user(userId);
        LocalDate current = GrowthTerms.termStartOf(AppTime.nowLocal().toLocalDate());
        TreeSet<LocalDate> terms = new TreeSet<>(Comparator.reverseOrder());
        terms.add(current);

        Map<LocalDate, List<ActivityParticipationApi.Participation>> activities = new HashMap<>();
        List<ActivityParticipationApi.Participation> attended = participation.attended(userId, ARCHIVE_ACTIVITY_LIMIT);
        for (ActivityParticipationApi.Participation item : attended) {
            LocalDate term = GrowthTerms.termStartOf(item.startsAt().toLocalDate());
            terms.add(term);
            activities.computeIfAbsent(term, key -> new ArrayList<>()).add(item);
        }

        AchievementDtos.AchievementView view = achievements.view(userId);
        Map<LocalDate, List<AchievementDtos.BadgeView>> badges = new HashMap<>();
        int badgeTotal = 0;
        for (AchievementDtos.BadgeView badge : view.badges()) {
            if (!badge.earned() || badge.earnedAt() == null) continue;
            badgeTotal++;
            LocalDate term = termOf(badge.earnedAt());
            terms.add(term);
            badges.computeIfAbsent(term, key -> new ArrayList<>()).add(badge);
        }

        Map<LocalDate, Integer> official = new HashMap<>();
        for (AchievementDtos.StageView stage : view.stages()) {
            for (AchievementDtos.TaskView task : stage.tasks()) {
                if (!task.completed() || task.completedAt() == null) continue;
                LocalDate term = termOf(task.completedAt());
                terms.add(term);
                official.merge(term, 1, Integer::sum);
            }
        }

        Map<LocalDate, Integer> personal = new HashMap<>();
        for (UserTask task : taskMapper.selectList(new LambdaQueryWrapper<UserTask>()
                .select(UserTask::getTermStart)
                .eq(UserTask::getUserId, userId).isNotNull(UserTask::getCompletedAt))) {
            terms.add(task.getTermStart());
            personal.merge(task.getTermStart(), 1, Integer::sum);
        }

        List<GrowthDtos.ArchiveTerm> result = new ArrayList<>();
        for (LocalDate term : terms) {
            List<ActivityParticipationApi.Participation> items = activities.getOrDefault(term, List.of());
            result.add(new GrowthDtos.ArchiveTerm(
                    term, GrowthTerms.termLabel(user.getEntryYear(), term), term.equals(current), items,
                    badges.getOrDefault(term, List.of()),
                    official.getOrDefault(term, 0), personal.getOrDefault(term, 0),
                    items.stream().mapToInt(ActivityParticipationApi.Participation::xpGranted).sum(),
                    items.stream().mapToInt(ActivityParticipationApi.Participation::coinsGranted).sum()));
        }
        return new GrowthDtos.ArchiveView(attended.size(), badgeTotal, result);
    }

    private static LocalDate termOf(String offsetTimestamp) {
        return GrowthTerms.termStartOf(OffsetDateTime.parse(offsetTimestamp)
                .atZoneSameInstant(AppTime.zone()).toLocalDate());
    }

    /** DATETIME 不存小数秒；先截断，响应与之后读回的时间才一致。 */
    private static LocalDateTime now() {
        return AppTime.nowLocal().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    }

    static final int RECENT_ACTIVITY_LIMIT = 6;

    /**
     * 主页上的成长展示。徽章与参加过的活动本来就是公开信息（活动报名名单公开）；
     * 年级/学期跟随对方的「展示年级」开关；双方任一方拉黑时只返回等级，不列活动和徽章。
     */
    @Transactional(readOnly = true)
    public GrowthDtos.PublicGrowthView publicGrowth(String uid, Long viewerId) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPublicUid, uid));
        if (user == null || !"active".equals(user.getStatus())) throw new NoSuchElementException("用户不存在或已注销");
        boolean mine = viewerId != null && viewerId.equals(user.getId());
        boolean hidden = viewerId != null && !mine && blockQueryApi.isBlockedEitherWay(viewerId, user.getId());
        String term = null;
        if (user.getEntryYear() != null && (mine || Boolean.TRUE.equals(user.getShowGrade()))) {
            term = GrowthTerms.termLabel(GrowthTerms.termIndex(user.getEntryYear(), AppTime.nowLocal().toLocalDate()));
        }
        int level = user.getLevel() == null ? 1 : user.getLevel();
        int xp = user.getXp() == null ? 0 : user.getXp();
        if (hidden) {
            return new GrowthDtos.PublicGrowthView(level, user.getLevelTitle(), xp, null, 0, List.of(), List.of(), null);
        }
        AchievementDtos.AchievementView achievement = achievements.view(user.getId());
        List<AchievementDtos.BadgeView> badges = achievement.badges().stream()
                .filter(AchievementDtos.BadgeView::earned).toList();
        ActivityParticipationApi.AttendanceSummary attendance = participation.attendance(user.getId(), 1);
        List<GrowthDtos.RecentActivity> recent = participation.attended(user.getId(), RECENT_ACTIVITY_LIMIT).stream()
                .map(item -> new GrowthDtos.RecentActivity(item.activityId(), item.title(), item.category(), item.startsAt()))
                .toList();
        return new GrowthDtos.PublicGrowthView(level, user.getLevelTitle(), xp, term, attendance.count(), badges, recent,
                achievement.title());
    }

    private User user(long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) throw new NoSuchElementException("用户不存在");
        return user;
    }
}
