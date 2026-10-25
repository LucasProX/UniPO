package com.biecuoguo.report.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("report_cases")
public class ReportCase {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String targetType;
    private Long targetId;
    private Long targetUserId;
    private Long schoolId;
    private Long collegeId;
    private Long majorId;
    private String openKey;
    private String status;
    private Integer reportCount;
    private String latestSnapshot;
    private LocalDateTime firstReportedAt;
    private LocalDateTime lastReportedAt;
    private Long assigneeId;
    private LocalDateTime claimedAt;
    private String resolution;
    private String resolutionNote;
    private Long handledBy;
    private LocalDateTime handledAt;
    private Integer version;
    private LocalDateTime createdAt;
}
