package com.biecuoguo.report.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("content_reports")
public class ContentReport {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long reporterId;
    private String source;
    private String targetType;
    private Long targetId;
    private Long targetUserId;
    private Long schoolId;
    private Long collegeId;
    private Long majorId;
    private String category;
    private String detail;
    private String snapshot;
    private String status;
    private Long handledBy;
    private LocalDateTime handledAt;
    private String resolution;
    private String handleNote;
    private LocalDateTime createdAt;
    private Long caseId;
}
