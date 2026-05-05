package com.biecuoguo.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

@Data
@TableName("post_daily_stats")
public class PostDailyStat {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long postId;
    private LocalDate statDate;
    private Integer likeCount;
    private Integer commentCount;
    private Integer shareCount;
}
