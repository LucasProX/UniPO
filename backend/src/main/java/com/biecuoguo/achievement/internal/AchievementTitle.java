package com.biecuoguo.achievement.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 主页称号（V41）：每人最多一行。 */
@Data
@TableName("achievement_titles")
public class AchievementTitle {
    @TableId(type = IdType.INPUT)
    private Long userId;
    private String badgeCode;
    private LocalDateTime updatedAt;
}
