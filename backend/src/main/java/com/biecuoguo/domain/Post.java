package com.biecuoguo.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
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
    private Integer likeCount;
    private Integer commentCount;
    private Integer favoriteCount;
    private Integer shareCount;
    private String status;
    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
