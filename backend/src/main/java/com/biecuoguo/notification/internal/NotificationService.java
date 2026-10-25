package com.biecuoguo.notification.internal;

import com.biecuoguo.config.AppTime;
import com.biecuoguo.notification.api.NotificationApi;
import com.biecuoguo.notification.api.NotificationDtos;
import com.biecuoguo.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Service
public class NotificationService implements NotificationApi {
    private final NotificationMapper mapper;

    public NotificationService(NotificationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void notify(Collection<Long> userIds, String type, String title, String body, Long activityId, String dedupeKey) {
        write(userIds, "ACTIVITY", type, title, body, activityId, null, dedupeKey);
    }

    @Override
    @Transactional
    public void notifyPost(Collection<Long> userIds, String type, String title, String body, long postId, String dedupeKey) {
        write(userIds, "SOCIAL", type, title, body, null, postId, dedupeKey);
    }

    @Override
    @Transactional
    public void notifySystem(Collection<Long> userIds, String type, String title, String body, Long postId, String dedupeKey) {
        write(userIds, "SYSTEM", type, title, body, null, postId, dedupeKey);
    }

    private void write(Collection<Long> userIds, String category, String type, String title, String body,
                       Long activityId, Long postId, String dedupeKey) {
        LocalDateTime now = AppTime.nowLocal();
        String safeTitle = title.length() > 80 ? title.substring(0, 80) : title;
        String safeBody = body.length() > 300 ? body.substring(0, 300) : body;
        for (Long userId : userIds) {
            if (userId != null) mapper.insert(userId, category, type, safeTitle, safeBody, activityId, postId, dedupeKey, now);
        }
    }

    @Transactional(readOnly = true)
    public NotificationDtos.NotificationPage page(CurrentUser actor, Long beforeId, Integer requestedLimit) {
        int limit = requestedLimit == null ? 20 : Math.max(1, Math.min(50, requestedLimit));
        List<NotificationMapper.Row> rows = mapper.page(actor.id(), beforeId, limit + 1);
        boolean more = rows.size() > limit;
        List<NotificationMapper.Row> page = more ? rows.subList(0, limit) : rows;
        return new NotificationDtos.NotificationPage(page.stream().map(row -> new NotificationDtos.NotificationView(
                row.id(), row.category(), row.type(), row.title(), row.body(), row.activityId(), row.readAt() != null,
                AppTime.toOffset(row.createdAt()), row.postId())).toList(),
                more ? String.valueOf(page.getLast().id()) : null, more, mapper.unread(actor.id()));
    }

    @Transactional
    public int readAll(CurrentUser actor) {
        return mapper.readAll(actor.id(), AppTime.nowLocal());
    }

    @Transactional(readOnly = true)
    public int unread(CurrentUser actor) {
        return mapper.unread(actor.id());
    }
}
