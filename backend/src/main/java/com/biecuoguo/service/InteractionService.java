package com.biecuoguo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.biecuoguo.domain.*;
import com.biecuoguo.dto.NoticeDtos;
import com.biecuoguo.dto.ReminderDtos;
import com.biecuoguo.mapper.*;
import com.biecuoguo.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class InteractionService {
    private final FavoriteMapper favoriteMapper;
    private final ReminderMapper reminderMapper;
    private final UserNoticeStatusMapper statusMapper;
    private final NoticeMapper noticeMapper;
    private final NoticeService noticeService;

    public InteractionService(FavoriteMapper favoriteMapper, ReminderMapper reminderMapper, UserNoticeStatusMapper statusMapper, NoticeMapper noticeMapper, NoticeService noticeService) {
        this.favoriteMapper = favoriteMapper;
        this.reminderMapper = reminderMapper;
        this.statusMapper = statusMapper;
        this.noticeMapper = noticeMapper;
        this.noticeService = noticeService;
    }

    public List<NoticeDtos.NoticeSummary> favorites(CurrentUser currentUser) {
        List<Long> ids = favoriteMapper.selectList(new LambdaQueryWrapper<Favorite>().eq(Favorite::getUserId, currentUser.id()))
                .stream()
                .map(Favorite::getNoticeId)
                .toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return noticeService.list(new NoticeDtos.NoticeQuery(null, null, null, "published"), currentUser, false)
                .stream()
                .filter(notice -> ids.contains(notice.id()))
                .toList();
    }

    @Transactional
    public void favorite(Long noticeId, CurrentUser currentUser) {
        requireNotice(noticeId);
        Favorite existing = favoriteMapper.selectOne(new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getUserId, currentUser.id())
                .eq(Favorite::getNoticeId, noticeId));
        if (existing != null) {
            return;
        }
        Favorite favorite = new Favorite();
        favorite.setUserId(currentUser.id());
        favorite.setNoticeId(noticeId);
        favorite.setCreatedAt(LocalDateTime.now());
        favoriteMapper.insert(favorite);
    }

    @Transactional
    public void unfavorite(Long noticeId, CurrentUser currentUser) {
        favoriteMapper.delete(new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getUserId, currentUser.id())
                .eq(Favorite::getNoticeId, noticeId));
    }

    public List<ReminderDtos.ReminderView> reminders(CurrentUser currentUser) {
        return reminderMapper.selectList(new LambdaQueryWrapper<Reminder>()
                        .eq(Reminder::getUserId, currentUser.id())
                        .orderByAsc(Reminder::getRemindAt))
                .stream()
                .map(reminder -> {
                    Notice notice = noticeMapper.selectById(reminder.getNoticeId());
                    return ReminderDtos.ReminderView.from(
                            reminder,
                            notice == null ? "已删除通知" : notice.getTitle(),
                            notice == null ? null : notice.getDeadlineAt()
                    );
                })
                .toList();
    }

    @Transactional
    public ReminderDtos.ReminderView createReminder(Long noticeId, ReminderDtos.ReminderRequest request, CurrentUser currentUser) {
        Notice notice = requireNotice(noticeId);
        Reminder existing = reminderMapper.selectOne(new LambdaQueryWrapper<Reminder>()
                .eq(Reminder::getUserId, currentUser.id())
                .eq(Reminder::getNoticeId, noticeId));
        Reminder reminder = existing == null ? new Reminder() : existing;
        reminder.setUserId(currentUser.id());
        reminder.setNoticeId(noticeId);
        reminder.setRemindAt(request.remindAt());
        reminder.setStatus("pending");
        reminder.setChannel(request.channel() == null || request.channel().isBlank() ? "in_app" : request.channel());
        reminder.setUpdatedAt(LocalDateTime.now());
        if (existing == null) {
            reminder.setCreatedAt(LocalDateTime.now());
            reminderMapper.insert(reminder);
        } else {
            reminderMapper.updateById(reminder);
        }
        return ReminderDtos.ReminderView.from(reminder, notice.getTitle(), notice.getDeadlineAt());
    }

    @Transactional
    public ReminderDtos.ReminderView updateReminder(Long id, ReminderDtos.ReminderRequest request, CurrentUser currentUser) {
        Reminder reminder = reminderMapper.selectById(id);
        if (reminder == null || !reminder.getUserId().equals(currentUser.id())) {
            throw new NoSuchElementException("提醒不存在");
        }
        reminder.setRemindAt(request.remindAt());
        reminder.setChannel(request.channel() == null ? reminder.getChannel() : request.channel());
        reminder.setUpdatedAt(LocalDateTime.now());
        reminderMapper.updateById(reminder);
        Notice notice = noticeMapper.selectById(reminder.getNoticeId());
        return ReminderDtos.ReminderView.from(reminder, notice.getTitle(), notice.getDeadlineAt());
    }

    @Transactional
    public void deleteReminder(Long id, CurrentUser currentUser) {
        reminderMapper.delete(new LambdaQueryWrapper<Reminder>().eq(Reminder::getId, id).eq(Reminder::getUserId, currentUser.id()));
    }

    @Transactional
    public void complete(Long noticeId, CurrentUser currentUser) {
        upsertStatus(noticeId, currentUser.id(), "completed");
    }

    @Transactional
    public void ignore(Long noticeId, CurrentUser currentUser) {
        upsertStatus(noticeId, currentUser.id(), "ignored");
    }

    private void upsertStatus(Long noticeId, Long userId, String status) {
        requireNotice(noticeId);
        UserNoticeStatus existing = statusMapper.selectOne(new LambdaQueryWrapper<UserNoticeStatus>()
                .eq(UserNoticeStatus::getUserId, userId)
                .eq(UserNoticeStatus::getNoticeId, noticeId));
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            existing = new UserNoticeStatus();
            existing.setUserId(userId);
            existing.setNoticeId(noticeId);
            existing.setCreatedAt(now);
        }
        existing.setStatus(status);
        existing.setCompletedAt("completed".equals(status) ? now : null);
        existing.setUpdatedAt(now);
        if (existing.getId() == null) {
            statusMapper.insert(existing);
        } else {
            statusMapper.updateById(existing);
        }
    }

    private Notice requireNotice(Long noticeId) {
        Notice notice = noticeMapper.selectById(noticeId);
        if (notice == null) {
            throw new NoSuchElementException("通知不存在");
        }
        return notice;
    }
}
