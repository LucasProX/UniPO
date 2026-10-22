package com.biecuoguo.post.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("posts")
public class Post {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long authorId;
    private Long schoolId;
    private Long collegeId;
    private Long majorId;
    private String board;
    private String sourceType;
    private String title;
    private String content;
    private String excerpt;
    private String coverUrl;
    private String riskLevel;
    private Boolean official;
    private Boolean dontMiss;
    private Boolean pinned;
    private LocalDateTime deadlineAt;
    private String missConsequence;
    private String nextAction;
    private String tags;
    @TableField(updateStrategy = FieldStrategy.NEVER) private Integer likeCount;
    @TableField(updateStrategy = FieldStrategy.NEVER) private Integer commentCount;
    @TableField(updateStrategy = FieldStrategy.NEVER) private Integer favoriteCount;
    @TableField(updateStrategy = FieldStrategy.NEVER) private Integer shareCount;
    private String status;
    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
