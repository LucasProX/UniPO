package com.biecuoguo.admin.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("admin_audit_logs")
public class AdminAuditLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long actorId;
    private String actorEmail;
    private String action;
    private String targetType;
    private String targetId;
    private String detail;
    private String requestId;
    private LocalDateTime createdAt;
}
