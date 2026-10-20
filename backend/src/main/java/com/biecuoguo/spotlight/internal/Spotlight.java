package com.biecuoguo.spotlight.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 首页轮播位。字段含义见 V12__spotlights.sql 的注释。 */
@Data
@TableName("spotlights")
public class Spotlight {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String subtitle;
    private String badge;
    private String imageUrl;
    private String linkUrl;
    private String source;
    private Long referencePostId;
    private Integer weight;
    private Boolean enabled;
    private LocalDateTime startsAt;
    private LocalDateTime endsAt;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
