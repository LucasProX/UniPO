package com.biecuoguo.achievement.internal;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("badges")
public class Badge {
    @TableId
    private String code;
    private String title;
    private String description;
    private String rarity;
    private String icon;
    private Integer sortOrder;
}
