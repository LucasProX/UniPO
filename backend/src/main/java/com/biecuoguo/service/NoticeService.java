package com.biecuoguo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.biecuoguo.domain.*;
import com.biecuoguo.dto.NoticeDtos;
import com.biecuoguo.mapper.*;
import com.biecuoguo.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class NoticeService {
    private final NoticeMapper noticeMapper;
    private final NoticeTagMapper noticeTagMapper;
    private final TagMapper tagMapper;
    private final CommentMapper commentMapper;
    private final FavoriteMapper favoriteMapper;
    private final ReminderMapper reminderMapper;
    private final UserNoticeStatusMapper statusMapper;
    private final TaxonomyService taxonomyService;

    public NoticeService(NoticeMapper noticeMapper, NoticeTagMapper noticeTagMapper, TagMapper tagMapper, CommentMapper commentMapper, FavoriteMapper favoriteMapper, ReminderMapper reminderMapper, UserNoticeStatusMapper statusMapper, TaxonomyService taxonomyService) {
        this.noticeMapper = noticeMapper;
        this.noticeTagMapper = noticeTagMapper;
        this.tagMapper = tagMapper;
        this.commentMapper = commentMapper;
        this.favoriteMapper = favoriteMapper;
        this.reminderMapper = reminderMapper;
        this.statusMapper = statusMapper;
        this.taxonomyService = taxonomyService;
    }

    public List<NoticeDtos.NoticeSummary> list(NoticeDtos.NoticeQuery query, CurrentUser currentUser, boolean admin) {
        LambdaQueryWrapper<Notice> wrapper = new LambdaQueryWrapper<>();
        if (!admin) {
            wrapper.eq(Notice::getStatus, "published");
        } else if (query.status() != null && !query.status().isBlank()) {
            wrapper.eq(Notice::getStatus, query.status());
        }
        if (query.categoryId() != null) {
            wrapper.eq(Notice::getCategoryId, query.categoryId());
        }
        if (query.q() != null && !query.q().isBlank()) {
            String keyword = query.q().trim();
            wrapper.and(w -> w.like(Notice::getTitle, keyword)
                    .or()
                    .like(Notice::getSummary, keyword)
                    .or()
                    .like(Notice::getNextAction, keyword)
                    .or()
                    .like(Notice::getMissConsequence, keyword));
        }
        applyFilter(wrapper, query.filter());
        wrapper.orderByDesc(Notice::getPinned)
                .orderByDesc(Notice::getRecommended)
                .orderByDesc(Notice::getUrgent)
                .orderByDesc(Notice::getHighRisk)
                .orderByDesc(Notice::getSortWeight)
                .orderByAsc(Notice::getDeadlineAt);
        List<Notice> notices = noticeMapper.selectList(wrapper);
        return toSummaries(notices, currentUser);
    }

    public List<NoticeDtos.NoticeSummary> recommended(CurrentUser currentUser) {
        List<NoticeDtos.NoticeSummary> summaries = list(new NoticeDtos.NoticeQuery(null, null, null, "published"), currentUser, false);
        return summaries.stream()
                .sorted(Comparator.comparingInt(this::score).reversed())
                .limit(20)
                .toList();
    }

    public NoticeDtos.NoticeDetail detail(Long id, CurrentUser currentUser, boolean admin) {
        Notice notice = noticeMapper.selectById(id);
        if (notice == null || (!admin && !"published".equals(notice.getStatus()))) {
            throw new NoSuchElementException("通知不存在");
        }
        return toDetail(notice, currentUser);
    }

    @Transactional
    public NoticeDtos.NoticeDetail create(NoticeDtos.NoticeRequest request, CurrentUser currentUser) {
        LocalDateTime now = LocalDateTime.now();
        Notice notice = NoticeDtos.apply(new Notice(), request);
        notice.setCreatedBy(currentUser.id());
        notice.setUpdatedBy(currentUser.id());
        notice.setCreatedAt(now);
        notice.setUpdatedAt(now);
        if ("published".equals(notice.getStatus())) {
            notice.setPublishedAt(now);
        }
        noticeMapper.insert(notice);
        replaceTags(notice.getId(), request.tagIds());
        return toDetail(noticeMapper.selectById(notice.getId()), currentUser);
    }

    @Transactional
    public NoticeDtos.NoticeDetail update(Long id, NoticeDtos.NoticeRequest request, CurrentUser currentUser) {
        Notice existing = noticeMapper.selectById(id);
        if (existing == null) {
            throw new NoSuchElementException("通知不存在");
        }
        boolean wasDraft = !"published".equals(existing.getStatus());
        NoticeDtos.apply(existing, request);
        existing.setUpdatedBy(currentUser.id());
        existing.setUpdatedAt(LocalDateTime.now());
        if (wasDraft && "published".equals(existing.getStatus())) {
            existing.setPublishedAt(LocalDateTime.now());
        }
        noticeMapper.updateById(existing);
        replaceTags(id, request.tagIds());
        return toDetail(noticeMapper.selectById(id), currentUser);
    }

    public void delete(Long id) {
        noticeMapper.deleteById(id);
    }

    @Transactional
    public NoticeDtos.NoticeDetail publish(Long id, CurrentUser currentUser) {
        Notice notice = noticeMapper.selectById(id);
        if (notice == null) {
            throw new NoSuchElementException("通知不存在");
        }
        notice.setStatus("published");
        notice.setPublishedAt(LocalDateTime.now());
        notice.setUpdatedBy(currentUser.id());
        notice.setUpdatedAt(LocalDateTime.now());
        noticeMapper.updateById(notice);
        return toDetail(notice, currentUser);
    }

    @Transactional
    public NoticeDtos.NoticeDetail unpublish(Long id, CurrentUser currentUser) {
        Notice notice = noticeMapper.selectById(id);
        if (notice == null) {
            throw new NoSuchElementException("通知不存在");
        }
        notice.setStatus("draft");
        notice.setUpdatedBy(currentUser.id());
        notice.setUpdatedAt(LocalDateTime.now());
        noticeMapper.updateById(notice);
        return toDetail(notice, currentUser);
    }

    public List<NoticeDtos.CalendarDay> calendar(CurrentUser currentUser) {
        LocalDate start = LocalDate.now().withDayOfMonth(1);
        LocalDate end = start.plusMonths(1).minusDays(1);
        List<Notice> notices = noticeMapper.selectList(new LambdaQueryWrapper<Notice>()
                .eq(Notice::getStatus, "published")
                .between(Notice::getDeadlineAt, start.atStartOfDay(), end.plusDays(1).atStartOfDay())
                .orderByAsc(Notice::getDeadlineAt));
        Map<LocalDate, List<Notice>> byDate = notices.stream()
                .collect(Collectors.groupingBy(n -> n.getDeadlineAt().toLocalDate(), LinkedHashMap::new, Collectors.toList()));
        return byDate.entrySet().stream()
                .map(entry -> {
                    List<NoticeDtos.NoticeSummary> summaries = toSummaries(entry.getValue(), currentUser);
                    return new NoticeDtos.CalendarDay(
                            entry.getKey().toString(),
                            summaries.size(),
                            summaries.stream().filter(NoticeDtos.NoticeSummary::highRisk).count(),
                            summaries
                    );
                })
                .toList();
    }

    private void applyFilter(LambdaQueryWrapper<Notice> wrapper, String filter) {
        if (filter == null || filter.isBlank() || "all".equals(filter)) {
            return;
        }
        switch (filter) {
            case "urgent" -> wrapper.eq(Notice::getUrgent, true);
            case "highRisk" -> wrapper.eq(Notice::getHighRisk, true);
            case "recommended" -> wrapper.eq(Notice::getRecommended, true);
            case "exam" -> wrapper.eq(Notice::getCategoryId, 1L);
            case "scholarship" -> wrapper.eq(Notice::getCategoryId, 2L);
            case "research" -> wrapper.eq(Notice::getCategoryId, 3L);
            case "internship" -> wrapper.eq(Notice::getCategoryId, 4L);
            default -> {}
        }
    }

    private int score(NoticeDtos.NoticeSummary notice) {
        int score = 0;
        if (notice.pinned()) score += 50;
        if (notice.recommended()) score += 30;
        if (notice.urgent()) score += 24;
        if (notice.highRisk()) score += 24;
        if ("high".equals(notice.importance())) score += 18;
        if ("medium".equals(notice.importance())) score += 8;
        if ("ignored".equals(notice.userStatus())) score -= 60;
        if (notice.deadlineAt() != null && notice.deadlineAt().isBefore(LocalDateTime.now().plusDays(3))) score += 16;
        return score + (notice.sortWeight() == null ? 0 : notice.sortWeight());
    }

    private List<NoticeDtos.NoticeSummary> toSummaries(List<Notice> notices, CurrentUser currentUser) {
        Map<Long, Category> categoryMap = taxonomyService.categoryMap();
        Map<Long, School> schoolMap = taxonomyService.schoolMap();
        Map<Long, List<NoticeDtos.TagView>> tagViews = tagViews(notices.stream().map(Notice::getId).toList());
        Set<Long> favorites = relationSet(currentUser, "favorite");
        Set<Long> reminders = relationSet(currentUser, "reminder");
        Map<Long, String> statuses = statusMap(currentUser);
        Map<Long, Long> commentCounts = commentCounts(notices.stream().map(Notice::getId).toList());
        return notices.stream()
                .map(notice -> toSummary(notice, categoryMap, schoolMap, tagViews, favorites, reminders, statuses, commentCounts))
                .toList();
    }

    private NoticeDtos.NoticeDetail toDetail(Notice notice, CurrentUser currentUser) {
        Map<Long, Category> categoryMap = taxonomyService.categoryMap();
        Map<Long, School> schoolMap = taxonomyService.schoolMap();
        List<NoticeDtos.TagView> tags = tagViews(List.of(notice.getId())).getOrDefault(notice.getId(), List.of());
        Set<Long> favorites = relationSet(currentUser, "favorite");
        Set<Long> reminders = relationSet(currentUser, "reminder");
        Map<Long, String> statuses = statusMap(currentUser);
        long commentCount = commentMapper.selectCount(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getNoticeId, notice.getId())
                .ne(Comment::getStatus, "hidden"));
        Category category = categoryMap.get(notice.getCategoryId());
        School school = schoolMap.get(notice.getSchoolId());
        return new NoticeDtos.NoticeDetail(
                notice.getId(),
                notice.getTitle(),
                notice.getSchoolId(),
                school == null ? "试点大学" : school.getName(),
                notice.getCollege(),
                notice.getGrade(),
                notice.getCategoryId(),
                category == null ? "未分类" : category.getName(),
                notice.getImportance(),
                notice.getRiskLevel(),
                notice.getDeadlineAt(),
                notice.getOfficialUrl(),
                notice.getAttachmentUrl(),
                notice.getSummary(),
                notice.getWhatIsIt(),
                notice.getWhyImportant(),
                notice.getSuitableFor(),
                notice.getNotSuitableFor(),
                notice.getMissConsequence(),
                notice.getNextAction(),
                notice.getMaterialsNeeded(),
                notice.getSeniorTip(),
                notice.getStatus(),
                notice.getRecommended(),
                notice.getPinned(),
                notice.getHighRisk(),
                notice.getUrgent(),
                notice.getSortWeight(),
                notice.getPublishedAt(),
                commentCount,
                favorites.contains(notice.getId()),
                reminders.contains(notice.getId()),
                statuses.getOrDefault(notice.getId(), "unread"),
                tags
        );
    }

    private NoticeDtos.NoticeSummary toSummary(Notice notice, Map<Long, Category> categoryMap, Map<Long, School> schoolMap, Map<Long, List<NoticeDtos.TagView>> tagViews, Set<Long> favorites, Set<Long> reminders, Map<Long, String> statuses, Map<Long, Long> commentCounts) {
        Category category = categoryMap.get(notice.getCategoryId());
        School school = schoolMap.get(notice.getSchoolId());
        return new NoticeDtos.NoticeSummary(
                notice.getId(),
                notice.getTitle(),
                notice.getSchoolId(),
                school == null ? "试点大学" : school.getName(),
                notice.getCategoryId(),
                category == null ? "未分类" : category.getName(),
                notice.getImportance(),
                notice.getRiskLevel(),
                notice.getDeadlineAt(),
                notice.getSummary(),
                notice.getMissConsequence(),
                notice.getNextAction(),
                notice.getSuitableFor(),
                notice.getStatus(),
                notice.getRecommended(),
                notice.getPinned(),
                notice.getHighRisk(),
                notice.getUrgent(),
                notice.getSortWeight(),
                commentCounts.getOrDefault(notice.getId(), 0L),
                favorites.contains(notice.getId()),
                reminders.contains(notice.getId()),
                statuses.getOrDefault(notice.getId(), "unread"),
                tagViews.getOrDefault(notice.getId(), List.of())
        );
    }

    private Map<Long, List<NoticeDtos.TagView>> tagViews(List<Long> noticeIds) {
        if (noticeIds.isEmpty()) {
            return Map.of();
        }
        List<NoticeTag> noticeTags = noticeTagMapper.selectList(new LambdaQueryWrapper<NoticeTag>().in(NoticeTag::getNoticeId, noticeIds));
        if (noticeTags.isEmpty()) {
            return Map.of();
        }
        Map<Long, Tag> tagMap = tagMapper.selectList(new LambdaQueryWrapper<Tag>()
                        .in(Tag::getId, noticeTags.stream().map(NoticeTag::getTagId).collect(Collectors.toSet())))
                .stream()
                .collect(Collectors.toMap(Tag::getId, Function.identity()));
        return noticeTags.stream()
                .filter(nt -> tagMap.containsKey(nt.getTagId()))
                .collect(Collectors.groupingBy(
                        NoticeTag::getNoticeId,
                        Collectors.mapping(nt -> NoticeDtos.TagView.from(tagMap.get(nt.getTagId())), Collectors.toList())
                ));
    }

    private Set<Long> relationSet(CurrentUser currentUser, String type) {
        if (currentUser == null) {
            return Set.of();
        }
        if ("favorite".equals(type)) {
            return favoriteMapper.selectList(new LambdaQueryWrapper<Favorite>().eq(Favorite::getUserId, currentUser.id()))
                    .stream()
                    .map(Favorite::getNoticeId)
                    .collect(Collectors.toSet());
        }
        return reminderMapper.selectList(new LambdaQueryWrapper<Reminder>().eq(Reminder::getUserId, currentUser.id()))
                .stream()
                .map(Reminder::getNoticeId)
                .collect(Collectors.toSet());
    }

    private Map<Long, String> statusMap(CurrentUser currentUser) {
        if (currentUser == null) {
            return Map.of();
        }
        return statusMapper.selectList(new LambdaQueryWrapper<UserNoticeStatus>().eq(UserNoticeStatus::getUserId, currentUser.id()))
                .stream()
                .collect(Collectors.toMap(UserNoticeStatus::getNoticeId, UserNoticeStatus::getStatus, (a, b) -> a));
    }

    private Map<Long, Long> commentCounts(List<Long> noticeIds) {
        if (noticeIds.isEmpty()) {
            return Map.of();
        }
        QueryWrapper<Comment> query = new QueryWrapper<>();
        query.select("notice_id", "count(*) as total")
                .in("notice_id", noticeIds)
                .ne("status", "hidden")
                .groupBy("notice_id");
        return commentMapper.selectMaps(query)
                .stream()
                .collect(Collectors.toMap(
                        row -> ((Number) row.get("notice_id")).longValue(),
                        row -> ((Number) row.get("total")).longValue()
                ));
    }

    private void replaceTags(Long noticeId, List<Long> tagIds) {
        noticeTagMapper.delete(new LambdaQueryWrapper<NoticeTag>().eq(NoticeTag::getNoticeId, noticeId));
        if (tagIds == null) {
            return;
        }
        tagIds.stream().distinct().forEach(tagId -> {
            NoticeTag noticeTag = new NoticeTag();
            noticeTag.setNoticeId(noticeId);
            noticeTag.setTagId(tagId);
            noticeTagMapper.insert(noticeTag);
        });
    }
}
