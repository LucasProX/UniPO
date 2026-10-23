package com.biecuoguo.notification.api;

import java.util.Collection;

/** 站内通知写入口。随业务事务一起提交；同一 dedupeKey 对同一用户只写一次。 */
public interface NotificationApi {
    void notify(Collection<Long> userIds, String type, String title, String body, Long activityId, String dedupeKey);
}
