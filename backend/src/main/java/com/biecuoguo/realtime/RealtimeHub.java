package com.biecuoguo.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.biecuoguo.security.AuthenticationContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 实时推送中心。连接注册在本进程，事件的跨实例分发交给 {@link RealtimeBroker}。
 *
 * <p>投递语义是「最多一次」：连接断了就断，不重发。要保证不丢靠客户端带 {@code lastEventId}
 * 重连后从 {@link RealtimeStore} 补，而不是服务端攒离线队列——离线消息是拉取接口的事。
 */
@Service
public class RealtimeHub {

    private static final Logger log = LoggerFactory.getLogger(RealtimeHub.class);
    private static final int REPLAY_LIMIT = 100;
    static final Duration VALIDATION_LEASE = Duration.ofSeconds(4);
    static final Duration WRITE_LIMIT = Duration.ofSeconds(2);

    private final Map<Long, Set<RealtimeConnection>> connections = new ConcurrentHashMap<>();
    private final RealtimeBroker broker;
    private final RealtimeStore store;
    private final ObjectMapper objectMapper;
    private final Counter publishedCounter;
    private final Counter deliveredCounter;
    private final Counter failedCounter;
    private final RealtimeSecurityExecutor security;
    private final LongSupplier ticker;

    @Autowired
    public RealtimeHub(RealtimeBroker broker, RealtimeStore store, ObjectMapper objectMapper,
                       MeterRegistry meterRegistry, RealtimeSecurityExecutor security) {
        this(broker, store, objectMapper, meterRegistry, security, System::nanoTime);
    }

    RealtimeHub(RealtimeBroker broker, RealtimeStore store, ObjectMapper objectMapper,
                MeterRegistry meterRegistry, RealtimeSecurityExecutor security, LongSupplier ticker) {
        this.broker = broker;
        this.store = store;
        this.objectMapper = objectMapper;
        this.security = security;
        this.ticker = ticker;
        this.publishedCounter = meterRegistry.counter("unipo.realtime.events.published");
        this.deliveredCounter = meterRegistry.counter("unipo.realtime.events.delivered");
        this.failedCounter = meterRegistry.counter("unipo.realtime.events.delivery.failed");
        // 在线连接数：Gauge 直接读 map，避免自己维护一个会漂移的计数器。
        meterRegistry.gauge("unipo.realtime.connections", connections, RealtimeHub::countConnections);
        broker.subscribe(this::deliverLocal);
    }

    private static double countConnections(Map<Long, Set<RealtimeConnection>> map) {
        return map.values().stream().mapToInt(Set::size).sum();
    }

    /** 一条本地连接。sink 收到的是已经序列化好的 JSON 帧。 */
    public RealtimeConnection register(AuthenticationContext identity, Consumer<String> sink, Runnable closeTransport) {
        RealtimeConnection connection = new RealtimeConnection(identity, sink, closeTransport);
        connections.compute(connection.userId(), (key, existing) -> {
            Set<RealtimeConnection> targets = existing == null ? ConcurrentHashMap.newKeySet() : existing;
            targets.add(connection);
            return targets;
        });
        // The identity may have been revoked between the HTTP handshake and registration.
        connection.requireValid();
        return connection;
    }

    /** 业务侧统一入口：落一份续传缓存，然后广播（单机=本地回调，多实例=Redis 频道）。 */
    public void publish(Long userId, String event, Object payload) {
        long seq = store.nextSeq();
        RealtimeEvent realtimeEvent = new RealtimeEvent(seq, event, System.currentTimeMillis(), payload);
        store.append(userId, realtimeEvent);
        publishedCounter.increment();
        broker.publish(new RealtimeBroker.BrokerMessage(userId, realtimeEvent));
    }

    /** 补发断线期间的事件；返回实际补发条数。 */
    public int replay(RealtimeConnection connection, long afterSeq) {
        if (!connection.requireValid()) { return 0; }
        var missed = store.after(connection.userId(), afterSeq, REPLAY_LIMIT);
        int delivered = 0;
        for (RealtimeEvent event : missed) {
            try {
                if (!connection.send(toJson(event))) { break; }
                delivered++;
                deliveredCounter.increment();
            } catch (RuntimeException failure) {
                failedCounter.increment();
                connection.close();
                break;
            }
        }
        return delivered;
    }

    void deliverLocal(RealtimeBroker.BrokerMessage message) {
        Set<RealtimeConnection> targets = connections.get(message.userId());
        if (targets == null || targets.isEmpty()) {
            return;
        }
        String json = toJson(message.event());
        for (RealtimeConnection connection : targets) {
            try {
                if (connection.send(json)) { deliveredCounter.increment(); }
            } catch (RuntimeException error) {
                // 客户端断开是常态，摘掉即可，别让它污染别的连接
                failedCounter.increment();
                connection.close();
                log.debug("realtime delivery drop user={}", message.userId());
            }
        }
    }

    public int connectionCount() {
        return connections.values().stream().mapToInt(Set::size).sum();
    }

    public void remove(RealtimeConnection connection) {
        connections.computeIfPresent(connection.userId(), (user, targets) -> {
            targets.remove(connection);
            return targets.isEmpty() ? null : targets;
        });
    }

    /** No authority lookup or socket write runs on this watchdog thread. */
    public void revalidateConnections() {
        long now = ticker.getAsLong();
        for (Set<RealtimeConnection> targets : connections.values()) {
            for (RealtimeConnection connection : targets) { connection.sweep(now); }
        }
    }

    private String toJson(RealtimeEvent event) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", "event");
        envelope.put("event", event.event());
        envelope.put("seq", event.seq());
        envelope.put("ts", event.ts());
        envelope.put("payload", event.payload());
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("实时事件序列化失败", e);
        }
    }

    /** 连接句柄；close() 幂等。 */
    public final class RealtimeConnection {
        private final AuthenticationContext identity;
        private final Consumer<String> sink;
        private final Runnable closeTransport;
        private final Object sendLock = new Object();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean validationPending = new AtomicBoolean();
        private final AtomicLong validationDeadline;
        private final AtomicReference<Long> writeStarted = new AtomicReference<>();

        RealtimeConnection(AuthenticationContext identity, Consumer<String> sink, Runnable closeTransport) {
            this.identity = identity;
            this.sink = sink;
            this.closeTransport = closeTransport;
            this.validationDeadline = new AtomicLong(ticker.getAsLong() + VALIDATION_LEASE.toNanos());
        }

        public Long userId() {
            return identity.user().id();
        }

        public boolean isClosed() { return closed.get(); }

        public boolean requireValid() {
            long started = ticker.getAsLong();
            if (closed.get() || started - validationDeadline.get() >= 0) { close(); return false; }
            if (!security.isValidNow(identity)) { close(); return false; }
            return acceptValidation(started);
        }

        public boolean send(String json) {
            synchronized (sendLock) {
                if (!requireValid() || closed.get()) { return false; }
                writeStarted.set(ticker.getAsLong());
                try {
                    if (closed.get()) { return false; }
                    sink.accept(json);
                    return !closed.get();
                } catch (RuntimeException failure) {
                    close();
                    throw failure;
                } finally {
                    writeStarted.set(null);
                }
            }
        }

        public void close() {
            if (closed.compareAndSet(false, true)) {
                remove(this);
                security.closeTransport(closeTransport);
            }
        }

        private boolean acceptValidation(long started) {
            long now = ticker.getAsLong();
            long next = started + VALIDATION_LEASE.toNanos();
            if (closed.get() || now - validationDeadline.get() >= 0 || now - next >= 0) {
                close(); return false;
            }
            validationDeadline.accumulateAndGet(next, (old, candidate) -> candidate - old > 0 ? candidate : old);
            return !closed.get();
        }

        private void sweep(long now) {
            Long writingSince = writeStarted.get();
            if (closed.get() || now - validationDeadline.get() >= 0
                    || (writingSince != null && now - writingSince >= WRITE_LIMIT.toNanos())) {
                close(); return;
            }
            if (!validationPending.compareAndSet(false, true)) { return; }
            long started = ticker.getAsLong();
            boolean submitted = security.validateAsync(identity, valid -> {
                validationPending.set(false);
                if (!valid) { close(); } else { acceptValidation(started); }
            });
            if (!submitted) { validationPending.set(false); close(); }
        }
    }
}
