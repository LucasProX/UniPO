package com.biecuoguo.message.internal;

import com.biecuoguo.config.AppTime;
import com.biecuoguo.domain.User;
import com.biecuoguo.mapper.UserMapper;
import com.biecuoguo.message.api.MessageDtos;
import com.biecuoguo.push.api.PushDeliveryException;
import com.biecuoguo.push.api.PushService;
import com.biecuoguo.realtime.RealtimeHub;
import com.biecuoguo.social.api.BlockQueryApi;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/** One delivery at a time, outside business transactions. Database leases survive worker restarts. */
@Component
public class MessageOutboxWorker {
    private static final Logger log = LoggerFactory.getLogger(MessageOutboxWorker.class);
    private static final Duration LEASE = Duration.ofSeconds(60);
    private static final int MAX_PER_PASS = 20;

    private final MessageOutboxStore store;
    private final MessageMapper messages;
    private final UserMapper users;
    private final BlockQueryApi blocks;
    private final RealtimeHub realtime;
    private final PushService push;
    private final Counter delivered;
    private final Counter suppressed;
    private final Counter retried;
    private final Counter continued;
    private final Counter failed;
    private final Counter leaseLost;
    private final AtomicLong pendingGauge = new AtomicLong();
    private final AtomicLong sendingGauge = new AtomicLong();
    private final AtomicLong failedGauge = new AtomicLong();
    private final AtomicLong oldestGauge = new AtomicLong();

    public MessageOutboxWorker(MessageOutboxStore store, MessageMapper messages, UserMapper users,
                               BlockQueryApi blocks, RealtimeHub realtime, PushService push,
                               MeterRegistry metrics) {
        this.store = store;
        this.messages = messages;
        this.users = users;
        this.blocks = blocks;
        this.realtime = realtime;
        this.push = push;
        delivered = metrics.counter("unipo.message.outbox.delivered");
        suppressed = metrics.counter("unipo.message.outbox.suppressed");
        retried = metrics.counter("unipo.message.outbox.retried");
        continued = metrics.counter("unipo.message.outbox.continued");
        failed = metrics.counter("unipo.message.outbox.failed");
        leaseLost = metrics.counter("unipo.message.outbox.lease.lost");
        metrics.gauge("unipo.message.outbox.pending", pendingGauge);
        metrics.gauge("unipo.message.outbox.sending", sendingGauge);
        metrics.gauge("unipo.message.outbox.failed.current", failedGauge);
        metrics.gauge("unipo.message.outbox.oldest.age.seconds", oldestGauge);
    }

    public int drainOnce() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox worker requires an independent execution boundary");
        }
        int processed = 0;
        while (processed < MAX_PER_PASS && !Thread.currentThread().isInterrupted()) {
            // Do not prefetch a batch whose later leases expire while a provider is slow.
            var claims = store.claimBatch(1, LEASE);
            if (claims.isEmpty()) { break; }
            deliver(claims.getFirst());
            processed++;
        }
        var summary = store.summary();
        pendingGauge.set(summary.pending());
        sendingGauge.set(summary.sending());
        failedGauge.set(summary.failed());
        oldestGauge.set(summary.oldestAgeSeconds());
        return processed;
    }

    private void deliver(MessageOutboxStore.Claim claim) {
        try {
            Message message = messages.selectById(claim.messageId());
            if (message == null) {
                finish(store.suppressed(claim), suppressed);
                return;
            }
            Set<Long> active = users.selectBatchIds(List.of(message.getSenderId(), message.getReceiverId())).stream()
                    .filter(user -> "active".equals(user.getStatus())).map(User::getId).collect(Collectors.toSet());
            if (!active.contains(message.getSenderId()) || !active.contains(message.getReceiverId())
                    || blocks.isBlockedEitherWay(message.getSenderId(), message.getReceiverId())) {
                finish(store.suppressed(claim), suppressed);
                return;
            }
            if (!store.owns(claim)) {
                leaseLost.increment();
                return;
            }
            if ("REALTIME".equals(claim.channel())) {
                var view = new MessageDtos.MessageView(message.getId(), message.getConversationId(),
                        message.getSenderId(), message.getReceiverId(), message.getContent(), message.getReadFlag(),
                        AppTime.toOffset(message.getCreatedAt()));
                realtime.publish(message.getReceiverId(), "message", Map.of(
                        "conversationId", message.getConversationId(), "message", view, "eventId", claim.eventId()));
            } else if ("PUSH".equals(claim.channel())) {
                String content = message.getContent();
                String preview = content.length() > 60 ? content.substring(0, 60) + "…" : content;
                push.send(message.getReceiverId(), "新私信", preview, Map.of(
                        "conversationId", String.valueOf(message.getConversationId()),
                        "messageId", String.valueOf(message.getId()), "eventId", claim.eventId()));
            } else {
                finish(store.failed(claim, "UNSUPPORTED_CHANNEL"), failed);
                return;
            }
            finish(store.delivered(claim), delivered);
        } catch (PushDeliveryException failure) {
            boolean changed = failure.retryable()
                    ? store.retry(claim, failure.code(), failure.retryAfter())
                    : store.failed(claim, failure.code());
            boolean moreTargets = "APNS_MORE_TARGETS".equals(failure.code());
            finish(changed, moreTargets ? continued : failure.retryable() ? retried : failed);
            if (!moreTargets) {
                log.warn("Message delivery postponed id={} channel={} code={}", claim.id(), claim.channel(), failure.code());
            }
        } catch (RuntimeException failure) {
            // Keep only a stable error code: provider exceptions can contain tokens or message bodies.
            finish(store.retry(claim, "DELIVERY_FAILED"), retried);
            log.warn("Message delivery postponed id={} channel={} code=DELIVERY_FAILED", claim.id(), claim.channel());
        }
    }

    private void finish(boolean changed, Counter result) {
        if (changed) { result.increment(); }
        else { leaseLost.increment(); }
    }
}
