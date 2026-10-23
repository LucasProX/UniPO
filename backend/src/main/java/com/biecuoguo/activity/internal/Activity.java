package com.biecuoguo.activity.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 校园活动。字段含义见 V27__activities.sql。计数只在锁住本行的事务里改。 */
@Data
@TableName("activities")
public class Activity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long schoolId;
    private Long organizerId;
    private String organizerName;
    private String title;
    private String category;
    private String coverUrl;
    private String description;
    private String notice;
    private String location;
    private LocalDateTime startsAt;
    private LocalDateTime endsAt;
    private LocalDateTime signupDeadline;
    private String audienceScope;
    private Long audienceCollegeId;
    private Long audienceMajorId;
    private Integer audienceEntryYear;
    private Integer capacity;
    private Boolean waitlistEnabled;
    private Integer rewardXp;
    private Integer rewardCoins;
    private String status;
    private Integer registeredCount;
    private Integer waitlistCount;
    /** 只在服务端使用，绝不进入任何视图或日志。 */
    private String checkinSecret;
    private String checkinCode;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
