package com.biecuoguo.message.internal;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** The worker never runs in the HTTP transaction's afterCommit resource context. */
@Component
@ConditionalOnProperty(prefix = "app.message.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MessageOutboxSchedule {
    private static final Logger log = LoggerFactory.getLogger(MessageOutboxSchedule.class);
    private final MessageOutboxWorker worker;
    private final long pollIntervalMs;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "message-outbox");
        thread.setDaemon(true);
        return thread;
    });

    public MessageOutboxSchedule(MessageOutboxWorker worker,
                                @Value("${app.message.outbox.poll-interval-ms:1000}") long pollIntervalMs) {
        if (pollIntervalMs < 100 || pollIntervalMs > 60_000) {
            throw new IllegalArgumentException("Outbox poll interval must be between 100 and 60000 milliseconds");
        }
        this.worker = worker;
        this.pollIntervalMs = pollIntervalMs;
    }

    @PostConstruct
    void start() {
        executor.scheduleWithFixedDelay(() -> {
            try {
                worker.drainOnce();
            } catch (RuntimeException unavailable) {
                // The claim lease permits another instance to recover after this process exits.
                log.warn("Message outbox poll failed code=OUTBOX_UNAVAILABLE");
            }
        }, pollIntervalMs, pollIntervalMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void close() { executor.shutdownNow(); }
}
