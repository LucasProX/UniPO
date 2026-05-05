package com.biecuoguo.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("notices")
public class Notice {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private Long schoolId;
    private String college;
    private String grade;
    private Long categoryId;
    private String importance;
    private String riskLevel;
    private LocalDateTime deadlineAt;
    private String officialUrl;
    private String attachmentUrl;
    private String summary;
    private String whatIsIt;
    private String whyImportant;
    private String suitableFor;
    private String notSuitableFor;
    private String missConsequence;
    private String nextAction;
    private String materialsNeeded;
    private String seniorTip;
    private String status;
    @TableField("is_recommended")
    private Boolean recommended;
    @TableField("is_pinned")
    private Boolean pinned;
    @TableField("is_high_risk")
    private Boolean highRisk;
    @TableField("is_urgent")
    private Boolean urgent;
    private Integer sortWeight;
    private LocalDateTime publishedAt;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
