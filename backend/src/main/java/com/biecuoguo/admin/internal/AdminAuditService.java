package com.biecuoguo.admin.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.biecuoguo.admin.api.AdminAuditApi;
import com.biecuoguo.config.AppTime;
import com.biecuoguo.security.CurrentUser;
import com.biecuoguo.web.RequestContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 后台审计。所有管理端写操作都应该走这里记一笔。
 *
 * <p>刻意做成「失败不阻断业务」：审计写失败只打日志，不回滚用户的封禁/下架操作——
 * 否则数据库抖动会让运营完全没法处理紧急内容。
 */
@Service
public class AdminAuditService implements AdminAuditApi {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditService.class);
    private static final int MAX_DETAIL = 1000;

    private final AdminAuditLogMapper mapper;

    public AdminAuditService(AdminAuditLogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void record(CurrentUser actor, String action, String targetType, Object targetId, String detail) {
        try {
            AdminAuditLog entry = new AdminAuditLog();
            entry.setActorId(actor == null ? null : actor.id());
            entry.setActorEmail(actor == null ? null : actor.email());
            entry.setAction(action);
            entry.setTargetType(targetType);
            entry.setTargetId(targetId == null ? null : String.valueOf(targetId));
            entry.setDetail(detail == null ? null : detail.substring(0, Math.min(detail.length(), MAX_DETAIL)));
            entry.setRequestId(RequestContextHolder.requestId());
            entry.setCreatedAt(LocalDateTime.now());
            mapper.insert(entry);
        } catch (RuntimeException error) {
            log.warn("审计日志写入失败 action={} target={}:{}", action, targetType, targetId, error);
        }
    }

    @Override
    public List<AuditView> recent(int limit) {
        int safe = Math.max(1, Math.min(limit, 200));
        return mapper.selectList(new LambdaQueryWrapper<AdminAuditLog>()
                        .orderByDesc(AdminAuditLog::getId)
                        .last("LIMIT " + safe))
                .stream().map(AdminAuditService::toView).toList();
    }

    private static AuditView toView(AdminAuditLog entry) {
        return new AuditView(entry.getId(), entry.getActorId(), entry.getActorEmail(), entry.getAction(),
                entry.getTargetType(), entry.getTargetId(), entry.getDetail(), entry.getRequestId(),
                entry.getCreatedAt() == null ? null : AppTime.toOffset(entry.getCreatedAt()).toString());
    }
}
