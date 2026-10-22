package com.biecuoguo.message.internal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * MySQL owns delivery intents and leases. No database transaction remains open while a worker sends.
 * A lease expires rather than being forgotten when an application process exits.
 */
@Service
public class MessageOutboxStore {
    private static final int MAX_BATCH = 50;
    private static final Duration MAX_LEASE = Duration.ofHours(1);
    private static final Duration MAX_MINIMUM_DELAY = Duration.ofHours(24);
    private static final Set<String> ERROR_CODES = Set.of(
            "DELIVERY_FAILED", "REALTIME_FAILED", "PUSH_FAILED", "RELATIONSHIP_UNAVAILABLE",
            "MESSAGE_UNAVAILABLE", "DEPENDENCY_UNAVAILABLE", "TRANSPORT_FAILED",
            "WORKER_INTERRUPTED", "INVALID_CHANNEL", "UNSUPPORTED_CHANNEL", "INTERNAL_ERROR",
            "APNS_SERVER_ERROR", "APNS_RATE_LIMITED", "APNS_REJECTED", "APNS_UNAVAILABLE",
            "APNS_TIMEOUT", "APNS_INVALID_PAYLOAD", "APNS_MORE_TARGETS"
    );

    private final MessageOutboxMapper mapper;
    private final TransactionTemplate transaction;

    public MessageOutboxStore(MessageOutboxMapper mapper, PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(5);
    }

    /** Called only inside the message write transaction; any outbox failure rolls that message back. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(long messageId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("消息投递记录必须与消息在同一事务保存");
        }
        if (messageId <= 0) { throw new IllegalArgumentException("消息 ID 不合法"); }
        mapper.enqueue(randomToken(), messageId, "REALTIME");
        mapper.enqueue(randomToken(), messageId, "PUSH");
    }

    /** Claims commit before this method returns. Never invoke from a business or transport transaction. */
    public List<Claim> claimBatch(int limit, Duration lease) {
        requireBackgroundCall();
        if (limit <= 0) { throw new IllegalArgumentException("投递批次大小必须大于零"); }
        Objects.requireNonNull(lease, "lease");
        if (lease.isNegative() || lease.isZero() || lease.compareTo(MAX_LEASE) > 0) {
            throw new IllegalArgumentException("投递租约必须大于零且不超过一小时");
        }
        long leaseMicros = lease.toNanos() / 1_000;
        if (leaseMicros == 0) { throw new IllegalArgumentException("投递租约精度不能小于一微秒"); }
        return Objects.requireNonNull(transaction.execute(status -> {
            List<Claim> claims = new ArrayList<>();
            for (MessageOutboxMapper.ReadyDelivery candidate : mapper.ready(Math.min(limit, MAX_BATCH))) {
                String token = randomToken();
                if (mapper.claim(candidate.id(), token, leaseMicros) == 1) {
                    int attempts = candidate.attempts() == Integer.MAX_VALUE
                            ? Integer.MAX_VALUE : candidate.attempts() + 1;
                    claims.add(new Claim(candidate.id(), candidate.eventId(), candidate.messageId(),
                            candidate.channel(), token, attempts));
                }
            }
            return List.copyOf(claims);
        }));
    }

    public boolean owns(Claim claim) {
        requireBackgroundCall();
        validateClaim(claim);
        return mapper.owns(claim.id(), claim.claimToken());
    }

    public boolean delivered(Claim claim) { return complete(claim, "DELIVERED", null); }

    public boolean suppressed(Claim claim) { return complete(claim, "SUPPRESSED", null); }

    public boolean retry(Claim claim, String errorCode) {
        return retry(claim, errorCode, Duration.ZERO);
    }

    public boolean retry(Claim claim, String errorCode, Duration minimumDelay) {
        requireBackgroundCall();
        validateClaim(claim);
        Objects.requireNonNull(minimumDelay, "minimumDelay");
        if (minimumDelay.isNegative() || minimumDelay.compareTo(MAX_MINIMUM_DELAY) > 0) {
            throw new IllegalArgumentException("投递重试最短等待必须在零到二十四小时之间");
        }
        long minimumDelayMicros = (minimumDelay.toNanos() + 999) / 1_000;
        return mapper.retry(claim.id(), claim.claimToken(), normalizeError(errorCode), minimumDelayMicros) == 1;
    }

    public boolean failed(Claim claim, String errorCode) { return complete(claim, "FAILED", normalizeError(errorCode)); }

    public Summary summary() {
        requireBackgroundCall();
        return mapper.summary();
    }

    private boolean complete(Claim claim, String state, String errorCode) {
        requireBackgroundCall();
        validateClaim(claim);
        return mapper.complete(claim.id(), claim.claimToken(), state, errorCode) == 1;
    }

    private static void requireBackgroundCall() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("投递状态操作不能在业务事务内执行");
        }
    }

    private static void validateClaim(Claim claim) {
        Objects.requireNonNull(claim, "claim");
        if (claim.id() <= 0 || claim.claimToken() == null || !claim.claimToken().matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("投递租约不合法");
        }
    }

    private static String randomToken() { return UUID.randomUUID().toString().replace("-", ""); }

    private static String normalizeError(String code) {
        return code != null && ERROR_CODES.contains(code) ? code : "DELIVERY_FAILED";
    }

    public record Claim(long id, String eventId, long messageId, String channel, String claimToken, int attempts) {}

    public record Summary(long pending, long sending, long failed, long oldestAgeSeconds) {}
}
