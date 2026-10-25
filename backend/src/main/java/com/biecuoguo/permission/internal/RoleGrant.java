package com.biecuoguo.permission.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("role_grants")
public class RoleGrant {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String role;
    private String scopeType;
    private Long scopeId;
    private Long schoolId;
    private Long grantedBy;
    private String reason;
    private LocalDateTime startsAt;
    private LocalDateTime expiresAt;
    private LocalDateTime revokedAt;
    private Long revokedBy;
    private String revokeReason;
    private LocalDateTime createdAt;
}
