package com.biecuoguo.growth.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 个人自定义任务，字段含义见 V30__growth_plan.sql。 */
@Data
@TableName("user_tasks")
public class UserTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private LocalDate termStart;
    private String title;
    private String note;
    private LocalDateTime dueAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
