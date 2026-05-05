package com.biecuoguo.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_preferences")
public class UserPreference {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String goalTags;
    private String interestCategories;
    private Boolean preferUrgent;
    private Boolean preferHighRisk;
    private Boolean preferSameMajor;
    private Boolean emailNotificationEnabled;
    private Boolean browserNotificationEnabled;
    private Integer remindBeforeHours;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
