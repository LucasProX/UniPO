package com.biecuoguo.message.internal;

import com.biecuoguo.admin.api.AdminAuditApi;
import com.biecuoguo.error.StateConflictException;
import com.biecuoguo.message.api.OutboxOperationsDtos;
import com.biecuoguo.security.CurrentUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

@Service
public class OutboxOperationsService {
    private static final Set<String> STATES = Set.of("PENDING", "SENDING", "DELIVERED", "SUPPRESSED", "FAILED");
    private static final Set<String> CHANNELS = Set.of("REALTIME", "PUSH");
    private final OutboxOperationsMapper mapper;
    private final AdminAuditApi audit;
    private final ObjectMapper json;

    public OutboxOperationsService(OutboxOperationsMapper mapper, AdminAuditApi audit, ObjectMapper json) {
        this.mapper = mapper;
        this.audit = audit;
        this.json = json;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public OutboxOperationsDtos.DeliveryPage list(String requestedState, String requestedChannel, String cursor,
                                                 Integer requestedLimit, CurrentUser actor) {
        requireAdmin(actor);
        String state = requestedState == null || requestedState.isBlank() ? "FAILED"
                : requestedState.trim().toUpperCase(Locale.ROOT);
        String channel = requestedChannel == null || requestedChannel.isBlank() ? null
                : requestedChannel.trim().toUpperCase(Locale.ROOT);
        if (!STATES.contains(state) || channel != null && !CHANNELS.contains(channel)) {
            throw new IllegalArgumentException("投递状态或通道不合法");
        }
        int limit = requestedLimit == null ? 20 : requestedLimit;
        if (limit < 1) { throw new IllegalArgumentException("分页数量必须大于零"); }
        limit = Math.min(limit, 50);
        Long before = decodeCursor(cursor, state, channel);
        List<OutboxOperationsMapper.DeliveryRow> rows = mapper.page(state, channel, before, limit + 1);
        boolean more = rows.size() > limit;
        List<OutboxOperationsMapper.DeliveryRow> page = more ? rows.subList(0, limit) : rows;
        String next = more ? encodeCursor(state, channel, page.getLast().id()) : null;
        return new OutboxOperationsDtos.DeliveryPage(page.stream().map(OutboxOperationsService::view).toList(),
                next, more, limit);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OutboxOperationsDtos.DeliveryView retry(long id, OutboxOperationsDtos.RetryRequest request,
                                                   CurrentUser actor) {
        requireAdmin(actor);
        if (id < 1 || request == null || request.expectedAttempts() == null || request.expectedAttempts() < 0) {
            throw new IllegalArgumentException("重试对象或尝试次数不合法");
        }
        String reason = normalizeReason(request.reason());
        var row = mapper.lock(id);
        if (row == null) { throw new NoSuchElementException("投递记录不存在"); }
        if (!"FAILED".equals(row.state()) || row.attempts() != request.expectedAttempts()) {
            throw new StateConflictException("投递状态已变化，请刷新后重试");
        }
        if (mapper.retryFailed(id, request.expectedAttempts()) != 1) {
            throw new StateConflictException("投递状态已变化，请刷新后重试");
        }
        audit.recordRequired(actor, "MESSAGE_DELIVERY_RETRY", "message_delivery", id,
                auditDetail(row, reason));
        // The worker cannot claim the row until this transaction and its audit commit together.
        var pending = mapper.lock(id);
        if (pending == null) { throw new IllegalStateException("投递重试结果无法读取"); }
        return view(pending);
    }

    private String auditDetail(OutboxOperationsMapper.DeliveryRow row, String reason) {
        try {
            return json.writeValueAsString(Map.of("previousState", "FAILED", "nextState", "PENDING",
                    "attempts", row.attempts(), "channel", row.channel(), "reason", reason));
        } catch (JsonProcessingException impossibleDetail) {
            throw new IllegalStateException("重试审计无法生成");
        }
    }

    private static void requireAdmin(CurrentUser actor) {
        if (actor == null || actor.id() == null || actor.id() <= 0 || !"ADMIN".equals(actor.role())) {
            throw new AccessDeniedException("仅管理员可以操作投递记录");
        }
    }

    private static String normalizeReason(String value) {
        String reason = value == null ? "" : value.trim();
        if (reason.isEmpty() || reason.length() > 200
                || reason.codePoints().anyMatch(ch -> Character.isISOControl(ch) || ch == 0x2028 || ch == 0x2029)) {
            throw new IllegalArgumentException("请填写一至二百字的单行重试原因");
        }
        return reason;
    }

    private static String encodeCursor(String state, String channel, long id) {
        String plain = "outbox1|" + state + "|" + (channel == null ? "-" : channel) + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.US_ASCII));
    }

    private static Long decodeCursor(String value, String state, String channel) {
        if (value == null || value.isBlank()) { return null; }
        if (value.length() > 160) { throw new IllegalArgumentException("分页游标不合法"); }
        try {
            String plain = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.US_ASCII);
            String[] parts = plain.split("\\|", -1);
            if (parts.length != 4 || !"outbox1".equals(parts[0]) || !state.equals(parts[1])
                    || !(channel == null ? "-" : channel).equals(parts[2]) || !parts[3].matches("[1-9][0-9]{0,18}")) {
                throw new IllegalArgumentException();
            }
            long id = Long.parseLong(parts[3]);
            if (!value.equals(encodeCursor(state, channel, id))) { throw new IllegalArgumentException(); }
            return id;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("分页游标不合法");
        }
    }

    private static OutboxOperationsDtos.DeliveryView view(OutboxOperationsMapper.DeliveryRow row) {
        return new OutboxOperationsDtos.DeliveryView(row.id(), row.eventId(), row.messageId(), row.channel(),
                row.state(), row.attempts(), row.lastError(), utc(row.createdAt()), utc(row.availableAt()),
                utc(row.leaseUntil()), utc(row.completedAt()));
    }

    private static OffsetDateTime utc(LocalDateTime value) { return value == null ? null : value.atOffset(ZoneOffset.UTC); }
}
