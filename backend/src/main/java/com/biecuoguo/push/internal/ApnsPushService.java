package com.biecuoguo.push.internal;

import com.biecuoguo.push.api.PushDeliveryException;
import com.biecuoguo.push.api.PushService;
import com.eatthepath.pushy.apns.ApnsClient;
import com.eatthepath.pushy.apns.ApnsClientBuilder;
import com.eatthepath.pushy.apns.DeliveryPriority;
import com.eatthepath.pushy.apns.PushNotificationResponse;
import com.eatthepath.pushy.apns.PushType;
import com.eatthepath.pushy.apns.auth.ApnsSigningKey;
import com.eatthepath.pushy.apns.util.SimpleApnsPushNotification;
import com.eatthepath.pushy.apns.util.concurrent.PushNotificationFuture;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * APNs 真实推送。只有 app.push.apns.enabled=true 时才创建，避免本地开发硬依赖 .p8。
 *
 * 需要的配置：
 *  - APNS_KEY_PATH  .p8 文件路径（AuthKey_XXXX.p8）
 *  - APNS_KEY_ID    10 位 Key ID
 *  - APNS_TEAM_ID   Apple Developer Team ID
 *  - APNS_TOPIC     bundle id，默认 com.unipo.app
 *  - APNS_PRODUCTION true/false
 */
@Service
@ConditionalOnProperty(prefix = "app.push.apns", name = "enabled", havingValue = "true")
public class ApnsPushService implements PushService {
    private static final Logger log = LoggerFactory.getLogger(ApnsPushService.class);
    private static final Duration NETWORK_BUDGET = Duration.ofSeconds(10);
    private static final Set<String> INVALID_DEVICE_REASONS = Set.of(
            "BadDeviceToken", "DeviceTokenNotForTopic", "Unregistered");

    private final PushDeliveryProgress progress;
    private final ObjectMapper objectMapper;
    private final String keyPath;
    private final String keyId;
    private final String teamId;
    private final String topic;
    private final boolean production;
    private final Duration networkBudget;
    private final LongSupplier ticker;

    private ApnsClient client;

    @Autowired
    public ApnsPushService(PushDeliveryProgress progress, ObjectMapper objectMapper,
                           @Value("${app.push.apns.key-path:}") String keyPath,
                           @Value("${app.push.apns.key-id:}") String keyId,
                           @Value("${app.push.apns.team-id:}") String teamId,
                           @Value("${app.push.apns.topic:com.unipo.app}") String topic,
                           @Value("${app.push.apns.production:false}") boolean production) {
        this(progress, objectMapper, keyPath, keyId, teamId, topic, production, null,
                NETWORK_BUDGET, System::nanoTime);
    }

    ApnsPushService(PushDeliveryProgress progress, ObjectMapper objectMapper, ApnsClient client,
                    Duration networkBudget, LongSupplier ticker) {
        this(progress, objectMapper, "", "", "", "com.unipo.app", false, client, networkBudget, ticker);
    }

    private ApnsPushService(PushDeliveryProgress progress, ObjectMapper objectMapper,
                            String keyPath, String keyId, String teamId, String topic, boolean production,
                            ApnsClient client, Duration networkBudget, LongSupplier ticker) {
        this.progress = progress;
        this.objectMapper = objectMapper;
        this.keyPath = keyPath;
        this.keyId = keyId;
        this.teamId = teamId;
        this.topic = topic;
        this.production = production;
        if (networkBudget.isZero() || networkBudget.isNegative() || networkBudget.compareTo(NETWORK_BUDGET) > 0) {
            throw new IllegalArgumentException("APNs network budget must be positive and no longer than ten seconds");
        }
        this.client = client;
        this.networkBudget = networkBudget;
        this.ticker = ticker;
    }

    @PostConstruct
    void init() throws Exception {
        if (keyPath == null || keyPath.isBlank() || keyId == null || keyId.isBlank()
                || teamId == null || teamId.isBlank()) {
            throw new IllegalStateException("APNs 已启用，但缺少 APNS_KEY_PATH / APNS_KEY_ID / APNS_TEAM_ID");
        }
        client = new ApnsClientBuilder()
                .setApnsServer(production ? ApnsClientBuilder.PRODUCTION_APNS_HOST
                        : ApnsClientBuilder.DEVELOPMENT_APNS_HOST)
                .setSigningKey(ApnsSigningKey.loadFromPkcs8File(new File(keyPath), teamId, keyId))
                .build();
        log.info("APNs client ready (production={}, topic={})", production, topic);
    }

    @PreDestroy
    void close() throws Exception {
        if (client != null) {
            client.close();
        }
    }

    @Override
    public void send(long userId, String title, String body, Map<String, String> data) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("APNs delivery requires an independent execution boundary");
        }
        if (client == null) { throw unavailable(); }
        final String eventId;
        final UUID apnsId;
        final String payload;
        try {
            eventId = data.getOrDefault("eventId", "").toLowerCase(Locale.ROOT);
            if (!eventId.matches("[0-9a-f]{32}")) { throw new IllegalArgumentException(); }
            apnsId = UUID.fromString(eventId.substring(0, 8) + "-" + eventId.substring(8, 12) + "-"
                    + eventId.substring(12, 16) + "-" + eventId.substring(16, 20) + "-" + eventId.substring(20));
            payload = objectMapper.writeValueAsString(Map.of(
                    "aps", Map.of("alert", Map.of("title", title, "body", body),
                            "sound", "default", "badge", 1),
                    "data", data
            ));
        } catch (Exception invalidPayload) {
            throw new PushDeliveryException("APNS_INVALID_PAYLOAD", false, Duration.ZERO);
        }

        final PushDeliveryProgress.Batch batch;
        final List<PushDeliveryProgress.Target> targets;
        try {
            batch = progress.open(eventId, userId);
            targets = progress.pending(batch, PushDeliveryProgress.PAGE_LIMIT);
        } catch (IllegalArgumentException invalidIdentity) {
            throw new PushDeliveryException("APNS_INVALID_PAYLOAD", false, Duration.ZERO);
        } catch (RuntimeException unavailableProgress) {
            throw unavailable();
        }

        // One deadline for the whole provider batch. Database lookup has its own pool/query timeouts.
        // Stable IDs help tracking and collapse pending notifications; they cannot retract delivered alerts.
        long deadline = ticker.getAsLong() + networkBudget.toNanos();
        List<PendingTarget> pending = new ArrayList<>();
        PushDeliveryException failure = null;
        int invalidDevices = 0;
        try {
            for (PushDeliveryProgress.Target target : targets) {
                if (deadline - ticker.getAsLong() <= 0) {
                    failure = preferFailure(failure, timeout());
                    break;
                }
                try {
                    // Ownership may change after selecting the page. This is the final provider-submission check.
                    if (!progress.isCurrentTarget(batch, target)) { continue; }
                    if (deadline - ticker.getAsLong() <= 0) {
                        failure = preferFailure(failure, timeout());
                        break;
                    }
                    SimpleApnsPushNotification notification = new SimpleApnsPushNotification(target.token(), topic, payload,
                            Instant.now().plus(Duration.ofHours(1)), DeliveryPriority.IMMEDIATE, PushType.ALERT,
                            eventId, apnsId);
                    var submitted = client.sendNotification(notification);
                    if (submitted == null) { failure = preferFailure(failure, unavailable()); }
                    else { pending.add(new PendingTarget(target, submitted)); }
                } catch (RuntimeException failedSubmission) {
                    failure = preferFailure(failure, unavailable());
                }
            }
            for (PendingTarget delivery : pending) {
                long remaining = deadline - ticker.getAsLong();
                if (remaining <= 0) {
                    failure = preferFailure(failure, timeout());
                    break;
                }
                try {
                    var response = delivery.future().get(remaining, TimeUnit.NANOSECONDS);
                    if (response == null) {
                        failure = preferFailure(failure, unavailable());
                    } else if (response.isAccepted()) {
                        progress.accepted(batch, delivery.target().id());
                    } else {
                        if (response.getStatusCode() == 410
                                || response.getRejectionReason().filter(INVALID_DEVICE_REASONS::contains).isPresent()) {
                            // Progress does not delete a credential that may since have been registered anew.
                            progress.invalid(batch, delivery.target().id());
                            invalidDevices++;
                        } else {
                            failure = preferFailure(failure, rejected(response.getStatusCode()));
                        }
                    }
                } catch (TimeoutException timedOut) {
                    failure = preferFailure(failure, timeout());
                    break;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw unavailable();
                } catch (ExecutionException | RuntimeException providerUnavailable) {
                    failure = preferFailure(failure, unavailable());
                }
            }
        } finally {
            pending.forEach(delivery -> { if (!delivery.future().isDone()) { delivery.future().cancel(false); } });
        }
        if (invalidDevices > 0) { log.debug("APNs skipped {} permanently invalid device registrations", invalidDevices); }
        if (failure != null) { throw failure; }
        try {
            if (progress.hasPending(batch)) {
                throw new PushDeliveryException("APNS_MORE_TARGETS", true, Duration.ZERO);
            }
        } catch (PushDeliveryException moreTargets) {
            throw moreTargets;
        } catch (RuntimeException unavailableProgress) {
            throw unavailable();
        }
    }

    private record PendingTarget(PushDeliveryProgress.Target target,
                                 PushNotificationFuture<SimpleApnsPushNotification,
                                         PushNotificationResponse<SimpleApnsPushNotification>> future) {}

    private static PushDeliveryException rejected(int status) {
        if (status >= 500) { return new PushDeliveryException("APNS_SERVER_ERROR", true, Duration.ofMinutes(15)); }
        if (status == 429) { return new PushDeliveryException("APNS_RATE_LIMITED", true, Duration.ofMinutes(1)); }
        return new PushDeliveryException("APNS_REJECTED", false, Duration.ZERO);
    }

    private static PushDeliveryException unavailable() {
        return new PushDeliveryException("APNS_UNAVAILABLE", true, Duration.ofSeconds(1));
    }

    private static PushDeliveryException timeout() {
        return new PushDeliveryException("APNS_TIMEOUT", true, Duration.ofSeconds(1));
    }

    private static PushDeliveryException preferFailure(PushDeliveryException previous, PushDeliveryException next) {
        if (previous == null || (!previous.retryable() && next.retryable())
                || (previous.retryable() == next.retryable() && previous.retryAfter().compareTo(next.retryAfter()) < 0)) {
            return next;
        }
        return previous;
    }
}
