package com.biecuoguo.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

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

    private final Map<Long, Set<RealtimeConnection>> connections = new ConcurrentHashMap<>();
    private final RealtimeBroker broker;
    private final RealtimeStore store;
    private final ObjectMapper objectMapper;
    private final Counter publishedCounter;
    private final Counter deliveredCounter;
    private final Counter failedCounter;

    public RealtimeHub(RealtimeBroker broker, RealtimeStore store, ObjectMapper objectMapper,
                       MeterRegistry meterRegistry) {
        this.broker = broker;
        this.store = store;
        this.objectMapper = objectMapper;
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
    public RealtimeConnection register(Long userId, Consumer<String> sink) {
        RealtimeConnection connection = new RealtimeConnection(userId, sink);
        connections.computeIfAbsent(userId, key -> ConcurrentHashMap.newKeySet()).add(connection);
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
    public int replay(Long userId, long afterSeq, Consumer<String> sink) {
        var missed = store.after(userId, afterSeq, REPLAY_LIMIT);
        for (RealtimeEvent event : missed) {
            sink.accept(toJson(event));
        }
        return missed.size();
    }

    void deliverLocal(RealtimeBroker.BrokerMessage message) {
        Set<RealtimeConnection> targets = connections.get(message.userId());
        if (targets == null || targets.isEmpty()) {
            return;
        }
        String json = toJson(message.event());
        for (RealtimeConnection connection : targets) {
            try {
                connection.send(json);
                deliveredCounter.increment();
            } catch (RuntimeException error) {
                // 客户端断开是常态，摘掉即可，别让它污染别的连接
                failedCounter.increment();
                remove(connection);
                log.debug("realtime delivery drop user={}", message.userId());
            }
        }
    }

    public int connectionCount() {
        return connections.values().stream().mapToInt(Set::size).sum();
    }

    public void remove(RealtimeConnection connection) {
        Set<RealtimeConnection> targets = connections.get(connection.userId());
        if (targets != null) {
            targets.remove(connection);
            if (targets.isEmpty()) {
                connections.remove(connection.userId());
            }
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
    public static final class RealtimeConnection {
        private final Long userId;
        private final Consumer<String> sink;
        private volatile boolean closed;

        RealtimeConnection(Long userId, Consumer<String> sink) {
            this.userId = userId;
            this.sink = sink;
        }

        public Long userId() {
            return userId;
        }

        public void send(String json) {
            if (!closed) {
                sink.accept(json);
            }
        }

        public void close() {
            closed = true;
        }
    }
}
