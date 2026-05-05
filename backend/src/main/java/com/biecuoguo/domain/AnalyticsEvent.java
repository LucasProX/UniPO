package com.biecuoguo.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("analytics_events")
public class AnalyticsEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String anonymousId;
    private String eventName;
    private String targetType;
    private Long targetId;
    private String propertiesJson;
    private LocalDateTime createdAt;
}
