package com.biecuoguo.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hub 的投递与续传语义。这里用假的 sink，不依赖真实 WebSocket 容器 ——
 * 容器层的行为（握手、帧、关闭）靠端到端的 WS 冒烟验。
 */
class RealtimeHubTest {

    private final MemoryRealtimeStore store = new MemoryRealtimeStore();
    private final LocalRealtimeBroker broker = new LocalRealtimeBroker();
    private final RealtimeHub hub = new RealtimeHub(broker, store, new ObjectMapper(), new SimpleMeterRegistry());

    @Test
    void publishWithoutConnectionIsNoopAndStillStored() {
        hub.publish(1L, "message", java.util.Map.of("x", 1));
        assertEquals(0, hub.connectionCount());
        assertEquals(1, store.after(1L, 0L, 10).size(), "没有连接也要落续传缓存");
    }

    @Test
    void deliversToEveryConnectionOfTheUserOnly() {
        List<String> phone = new ArrayList<>();
        List<String> laptop = new ArrayList<>();
        List<String> other = new ArrayList<>();
        hub.register(7L, phone::add);
        hub.register(7L, laptop::add);
        hub.register(8L, other::add);

        hub.publish(7L, "message", java.util.Map.of("conversationId", 3));

        assertEquals(1, phone.size());
        assertEquals(1, laptop.size());
        assertEquals(0, other.size());
        assertTrue(phone.get(0).contains("\"type\":\"event\""));
        assertTrue(phone.get(0).contains("\"event\":\"message\""));
    }

    @Test
    void replaySendsOnlyEventsAfterCursor() {
        hub.publish(9L, "message", java.util.Map.of("n", 1));
        hub.publish(9L, "message", java.util.Map.of("n", 2));
        hub.publish(9L, "message", java.util.Map.of("n", 3));

        List<String> frames = new ArrayList<>();
        int count = hub.replay(9L, 1L, frames::add);

        assertEquals(2, count);
        assertTrue(frames.get(0).contains("\"n\":2"));
        assertTrue(frames.get(1).contains("\"n\":3"));
    }

    @Test
    void closedConnectionStopsReceiving() {
        List<String> frames = new ArrayList<>();
        RealtimeHub.RealtimeConnection connection = hub.register(11L, frames::add);
        hub.publish(11L, "message", java.util.Map.of("n", 1));
        connection.close();
        hub.publish(11L, "message", java.util.Map.of("n", 2));

        assertEquals(1, frames.size());
    }
}
