package com.biecuoguo.achievement.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("achievement_stages")
public class AchievementStage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String title;
    private String subtitle;
    private Integer stageOrder;
    private Boolean boss;
}
