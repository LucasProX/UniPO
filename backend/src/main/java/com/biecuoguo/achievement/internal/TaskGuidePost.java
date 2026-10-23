package com.biecuoguo.achievement.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 任务关联的攻略帖（V32）。是否可见由帖子自己的可见性和有效期决定。 */
@Data
@TableName("achievement_task_posts")
public class TaskGuidePost {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private Long postId;
    private Integer sortOrder;
    private Long addedBy;
    private LocalDateTime createdAt;
}
