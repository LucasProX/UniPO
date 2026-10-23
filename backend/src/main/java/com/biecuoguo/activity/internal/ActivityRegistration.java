package com.biecuoguo.activity.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 每人每场只有一行；取消后再报名复用这一行并刷新 queuedAt。 */
@Data
@TableName("activity_registrations")
public class ActivityRegistration {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long activityId;
    private Long userId;
    private String status;
    private LocalDateTime queuedAt;
    private LocalDateTime cancelledAt;
    private LocalDateTime checkedInAt;
    private String checkinMethod;
    private Integer rewardXpGranted;
    private Integer rewardCoinsGranted;
    private Long reviewedBy;
    private LocalDateTime reviewedAt;
    private LocalDateTime checkinRevokedAt;
    private String revokeReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
