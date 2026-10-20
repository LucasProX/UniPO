package com.biecuoguo.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RealtimeHubTest {
    private final RealtimeHub hub = new RealtimeHub();

    @AfterEach
    void tearDown() {
        hub.shutdown();
    }

    @Test
    void publishWithoutConnectionIsNoop() {
        assertDoesNotThrow(() -> hub.publish(1L, "message", Map.of("conversationId", 1L)));
        assertEquals(0, hub.connectionCount());
    }

    @Test
    void subscribeRegistersConnectionPerUser() {
        hub.subscribe(7L);
        // 同一个用户开两个标签页 = 两条连接，两边都要收到
        hub.subscribe(7L);
        hub.subscribe(8L);
        assertEquals(3, hub.connectionCount());
        assertDoesNotThrow(() -> hub.publish(7L, "message", Map.of("conversationId", 2L)));
    }

    @Test
    void disconnectReleasesConnection() {
        SseEmitter emitter = hub.subscribe(7L);
        assertEquals(1, hub.connectionCount());
        // 客户端断开（切页/断网）走的是这条路径：不摘掉就是内存泄漏
        hub.remove(7L, emitter);
        assertEquals(0, hub.connectionCount());
        assertDoesNotThrow(() -> hub.publish(7L, "message", Map.of("conversationId", 3L)));
    }

    @Test
    void completingEmitterIsTolerated() {
        SseEmitter first = hub.subscribe(9L);
        SseEmitter second = hub.subscribe(9L);
        first.complete();
        hub.remove(9L, first);
        // 一条断了不影响另一条继续收
        assertDoesNotThrow(() -> hub.publish(9L, "message", Map.of("conversationId", 4L)));
        hub.remove(9L, second);
        assertEquals(0, hub.connectionCount());
    }
}
