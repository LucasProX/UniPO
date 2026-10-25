package com.biecuoguo.notification.api;

import java.util.Collection;

/** 站内通知写入口。随业务事务一起提交；同一 dedupeKey 对同一用户只写一次。 */
public interface NotificationApi {
    void notify(Collection<Long> userIds, String type, String title, String body, Long activityId, String dedupeKey);

    /** 社交类通知（关注的人发帖等），点开进入帖子。 */
    void notifyPost(Collection<Long> userIds, String type, String title, String body, long postId, String dedupeKey);

    /** 系统通知（举报处理结果、内容下架、审核通过等）；postId 可为空，有值时点开进入帖子。 */
    void notifySystem(Collection<Long> userIds, String type, String title, String body, Long postId, String dedupeKey);
}
