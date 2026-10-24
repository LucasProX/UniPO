package com.biecuoguo.activity.api;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

/** 成长模块读取「我和活动的关系」的只读入口；不暴露报名表与活动表。 */
public interface ActivityParticipationApi {

    /** 一条报名记录在成长视角下的样子。checkedIn 表示签到有效（未被撤销）。 */
    record Participation(
            long activityId,
            String title,
            String category,
            String location,
            String organizerName,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            /** PUBLISHED / CANCELLED */
            String activityStatus,
            /** REGISTERED / WAITLISTED / PENDING / REJECTED */
            String registrationStatus,
            boolean checkedIn,
            OffsetDateTime checkedInAt,
            boolean checkinRevoked,
            int xpGranted,
            int coinsGranted
    ) {}

    /** 开始时间落在 [from, to) 的、我报过名且没有自己取消的活动，按开始时间正序。 */
    List<Participation> participations(long userId, LocalDateTime from, LocalDateTime to);

    /** 签到有效的活动，按开始时间倒序，最多 limit 条。 */
    List<Participation> attended(long userId, int limit);

    /** 签到有效的场次与最早的若干次签到时间（升序），用来推导活动徽章。 */
    record AttendanceSummary(int count, List<LocalDateTime> earliestCheckins) {}

    AttendanceSummary attendance(long userId, int earliest);

    /** 帖子挂活动时用：活动标题、所属学校和状态。 */
    record ActivityRef(long id, String title, long schoolId, String status) {}

    java.util.Map<Long, ActivityRef> refs(java.util.Collection<Long> ids);
}
