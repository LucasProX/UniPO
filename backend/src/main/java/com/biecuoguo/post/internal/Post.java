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
    /** 推荐流排序分（V39 生成列，MySQL 按互动和发布时间算），只读。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER) private Double hotScore;
    private String status;
    private LocalDateTime publishedAt;
    /** 作者最近一次编辑的时间（V41）；没编辑过为空。 */
    private LocalDateTime editedAt;
    /** 运营最近一次核实攻略仍然有效的日期。 */
    private java.time.LocalDate verifiedAt;
    /** 攻略有效期；过期后不再作为任务的有效攻略。 */
    private LocalDateTime expiresAt;
    /** 关联的校园活动（V33）。 */
    private Long activityId;
    /** 关联的主线任务（V33）。 */
    private Long taskId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
