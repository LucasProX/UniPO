package com.biecuoguo.achievement.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.biecuoguo.achievement.api.AchievementDtos;
import com.biecuoguo.admin.api.AdminAuditApi;
import com.biecuoguo.config.AppTime;
import com.biecuoguo.error.StateConflictException;
import com.biecuoguo.post.api.PostAdminApi;
import com.biecuoguo.post.api.PostDtos;
import com.biecuoguo.security.CurrentUser;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 运营维护任务的攻略关联（G06）。只能挂已发布的帖子；之后下架、过期的帖子不会出现在用户的任务详情里，
 * 但关联保留在这里，方便运营看到「哪些攻略失效了」再续期或移除。写操作都记必需审计。
 */
@Service
public class TaskGuideAdminService {
    static final int MAX_GUIDES_PER_TASK = 10;

    private final AchievementTaskMapper taskMapper;
    private final TaskGuidePostMapper guideMapper;
    private final PostAdminApi postAdminApi;
    private final AdminAuditApi audit;

    public TaskGuideAdminService(AchievementTaskMapper taskMapper, TaskGuidePostMapper guideMapper,
                                 PostAdminApi postAdminApi, AdminAuditApi audit) {
        this.taskMapper = taskMapper;
        this.guideMapper = guideMapper;
        this.postAdminApi = postAdminApi;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<AchievementDtos.GuideLinkView> list(long taskId) {
        requireTask(taskId);
        List<TaskGuidePost> links = links(taskId);
        Map<Long, PostDtos.PostView> posts = postAdminApi.byIds(links.stream().map(TaskGuidePost::getPostId).toList())
                .stream().collect(Collectors.toMap(PostDtos.PostView::id, Function.identity()));
        return links.stream().map(link -> new AchievementDtos.GuideLinkView(
                link.getPostId(), link.getSortOrder() == null ? 0 : link.getSortOrder(),
                AppTime.toOffset(link.getCreatedAt()).toString(), posts.get(link.getPostId()))).toList();
    }

    @Transactional
    public List<AchievementDtos.GuideLinkView> add(long taskId, AchievementDtos.GuideLinkRequest request, CurrentUser actor) {
        // 锁任务行，串行同一任务的关联变更，数量上限因此不会被并发突破。
        if (taskMapper.lockById(taskId) == null) throw new NoSuchElementException("任务不存在");
        List<PostDtos.PostView> post = postAdminApi.byIds(List.of(request.postId()));
        if (post.isEmpty()) throw new NoSuchElementException("帖子不存在");
        List<TaskGuidePost> existing = links(taskId);
        // 已经关联过的直接幂等返回，不因为帖子之后过期或下架而报错。
        if (existing.stream().anyMatch(link -> link.getPostId().equals(request.postId()))) {
            return list(taskId);
        }
        if (!"published".equals(post.get(0).status())) throw new StateConflictException("只能关联已发布的帖子");
        if (Boolean.TRUE.equals(post.get(0).expired())) throw new StateConflictException("这篇帖子已过有效期，请先续期");
        if (existing.size() >= MAX_GUIDES_PER_TASK) {
            throw new StateConflictException("每个任务最多关联 " + MAX_GUIDES_PER_TASK + " 篇攻略");
        }
        TaskGuidePost link = new TaskGuidePost();
        link.setTaskId(taskId);
        link.setPostId(request.postId());
        link.setSortOrder(request.sortOrder() == null ? existing.size() : request.sortOrder());
        link.setAddedBy(actor.id());
        link.setCreatedAt(AppTime.nowLocal().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        try {
            guideMapper.insert(link);
        } catch (DuplicateKeyException ignored) {
            // 唯一键兜底：重复关联视为成功
        }
        audit.recordRequired(actor, "TASK_GUIDE_ADD", "achievement_task", taskId, "post=" + request.postId());
        return list(taskId);
    }

    @Transactional
    public List<AchievementDtos.GuideLinkView> remove(long taskId, long postId, CurrentUser actor) {
        if (taskMapper.lockById(taskId) == null) throw new NoSuchElementException("任务不存在");
        int removed = guideMapper.delete(new LambdaQueryWrapper<TaskGuidePost>()
                .eq(TaskGuidePost::getTaskId, taskId).eq(TaskGuidePost::getPostId, postId));
        if (removed > 0) {
            audit.recordRequired(actor, "TASK_GUIDE_REMOVE", "achievement_task", taskId, "post=" + postId);
        }
        return list(taskId);
    }

    private List<TaskGuidePost> links(long taskId) {
        return guideMapper.selectList(new LambdaQueryWrapper<TaskGuidePost>()
                .eq(TaskGuidePost::getTaskId, taskId)
                .orderByAsc(TaskGuidePost::getSortOrder).orderByAsc(TaskGuidePost::getId));
    }

    private void requireTask(long taskId) {
        if (taskMapper.selectById(taskId) == null) throw new NoSuchElementException("任务不存在");
    }
}
