package com.biecuoguo.notification.api;

import java.time.OffsetDateTime;
import java.util.List;

public final class NotificationDtos {
    private NotificationDtos() {}

    public record NotificationView(long id, String category, String type, String title, String body,
                                   Long activityId, boolean read, OffsetDateTime createdAt,
                                   /** 与帖子相关的通知；点开进入帖子详情。 */
                                   Long postId) {}

    public record NotificationPage(List<NotificationView> items, String nextCursor, boolean hasMore, int unreadCount) {}
}
