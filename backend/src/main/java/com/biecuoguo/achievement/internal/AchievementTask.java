package com.biecuoguo.achievement.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("achievement_tasks")
public class AchievementTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long stageId;
    private String title;
    private String description;
    private Integer taskOrder;
    private Integer xp;
    private String badgeCode;
}
