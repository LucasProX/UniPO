package com.biecuoguo.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.biecuoguo.security.AuthenticationContext;
import com.biecuoguo.security.AuthenticationStateService;
import com.biecuoguo.social.api.BlockQueryApi;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static com.biecuoguo.realtime.RealtimeTestSupport.identity;

/**
 * Hub 的投递与续传语义。这里用假的 sink，不依赖真实 WebSocket 容器 ——
 * 容器层的行为（握手、帧、关闭）靠端到端的 WS 冒烟验。
 */
class RealtimeHubTest {

    private final MemoryRealtimeStore store = new MemoryRealtimeStore();
    private final LocalRealtimeBroker broker = new LocalRealtimeBroker();
    private final AuthenticationStateService authentication = mock(AuthenticationStateService.class);
    private final RealtimeTestSupport.MutableClock clock = new RealtimeTestSupport.MutableClock();
    private final RealtimeTestSupport.ControlledExecutor verification = new RealtimeTestSupport.ControlledExecutor();
    private final RealtimeTestSupport.ControlledExecutor closes = new RealtimeTestSupport.ControlledExecutor();
    private final RealtimeSecurityExecutor security = new RealtimeSecurityExecutor(authentication, verification, closes, Duration.ofSeconds(1));
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final BlockQueryApi blocks = mock(BlockQueryApi.class);
    private final RealtimeHub hub = new RealtimeHub(broker, store, new ObjectMapper(), metrics, security, blocks, clock::nanoTime);

    @BeforeEach
    void validByDefault() { when(authentication.isValid(any())).thenReturn(true); }

    @Test
    void messagesAreLiveOnlyAndDoNotAdvanceReplaySequence() {
        hub.publish(1L, "message", messagePayload(2L));
        hub.publish(1L, "profile-update", java.util.Map.of("x", 1));

        assertEquals(0, hub.connectionCount());
        assertEquals(1, store.after(1L, 0L, 10).size());
        assertEquals(1L, store.after(1L, 0L, 10).get(0).seq(), "私信不能消耗可重放事件序号");
    }

    @Test
    void deliversToEveryConnectionOfTheUserOnly() {
        List<String> phone = new ArrayList<>();
        List<String> laptop = new ArrayList<>();
        List<String> other = new ArrayList<>();
        hub.register(identity(7L, "phone"), phone::add, () -> {});
        hub.register(identity(7L, "laptop"), laptop::add, () -> {});
        hub.register(identity(8L, "other"), other::add, () -> {});

        hub.publish(7L, "message", messagePayload(8L));

        assertEquals(1, phone.size());
        assertEquals(1, laptop.size());
        assertEquals(0, other.size());
        assertTrue(phone.get(0).contains("\"type\":\"event\""));
        assertTrue(phone.get(0).contains("\"event\":\"message\""));
    }

    @Test
    void replaySendsOnlyEventsAfterCursor() {
        hub.publish(9L, "profile-update", java.util.Map.of("n", 1));
        hub.publish(9L, "profile-update", java.util.Map.of("n", 2));
        hub.publish(9L, "profile-update", java.util.Map.of("n", 3));

        List<String> frames = new ArrayList<>();
        var connection = hub.register(identity(9L, "phone"), frames::add, () -> {});
        int count = hub.replay(connection, 1L);

        assertEquals(2, count);
        assertTrue(frames.get(0).contains("\"n\":2"));
        assertTrue(frames.get(1).contains("\"n\":3"));
    }

    @Test
    void oldCachedMessagesAreSkippedDuringReplay() {
        store.append(9L, new RealtimeEvent(1L, "message", 1L, messagePayload(7L)));
        List<String> frames = new ArrayList<>();
        var connection = hub.register(identity(9L, "phone"), frames::add, () -> {});

        assertEquals(0, hub.replay(connection, 0L));
        assertTrue(frames.isEmpty());
    }

    @Test
    void blockedOrUnverifiableLiveMessagesAreNotDelivered() {
        List<String> frames = new ArrayList<>();
        hub.register(identity(9L, "phone"), frames::add, () -> {});
        when(blocks.isBlockedEitherWay(7L, 9L)).thenReturn(true);

        hub.publish(9L, "message", messagePayload(7L));
        assertTrue(frames.isEmpty());

        when(blocks.isBlockedEitherWay(8L, 9L)).thenThrow(new IllegalStateException("fixture unavailable"));
        hub.publish(9L, "message", messagePayload(8L));
        assertTrue(frames.isEmpty(), "Relationship lookup failures must fail closed");
    }

    @Test
    void closedConnectionStopsReceiving() {
        List<String> frames = new ArrayList<>();
        RealtimeHub.RealtimeConnection connection = hub.register(identity(11L, "phone"), frames::add, () -> {});
        hub.publish(11L, "message", messagePayload(12L));
        connection.close();
        hub.publish(11L, "message", messagePayload(12L));

        assertEquals(1, frames.size());
        assertEquals(0, hub.connectionCount());
    }

    @Test
    void deviceRevocationStopsOnlyThatConnectionAndCountsOnlySuccessfulDelivery() {
        AuthenticationContext phoneIdentity = identity(7L, "phone");
        Set<AuthenticationContext> revoked = ConcurrentHashMap.newKeySet();
        when(authentication.isValid(any())).thenAnswer(call -> !revoked.contains(call.getArgument(0)));
        List<String> phone = new ArrayList<>();
        List<String> laptop = new ArrayList<>();
        AtomicInteger closed = new AtomicInteger();
        hub.register(phoneIdentity, phone::add, closed::incrementAndGet);
        hub.register(identity(7L, "laptop"), laptop::add, () -> {});

        revoked.add(phoneIdentity);
        hub.publish(7L, "private", java.util.Map.of("n", 1));

        assertTrue(phone.isEmpty());
        assertEquals(1, laptop.size());
        assertEquals(1, closed.get());
        assertEquals(1, hub.connectionCount());
        assertEquals(1, metrics.counter("unipo.realtime.events.delivered").count());
    }

    @Test
    void anotherHubRejectsRevokedIdentityWithoutAnInvalidationBroadcast() {
        AtomicBoolean valid = new AtomicBoolean(true);
        when(authentication.isValid(any())).thenAnswer(call -> valid.get());
        RealtimeHub second = new RealtimeHub(new LocalRealtimeBroker(), store, new ObjectMapper(),
                new SimpleMeterRegistry(), security, blocks, clock::nanoTime);
        List<String> frames = new ArrayList<>();
        AtomicInteger closed = new AtomicInteger();
        second.register(identity(7L, "second-node"), frames::add, closed::incrementAndGet);

        valid.set(false); // A mutation completed against the shared authority, with no Broker message.
        second.publish(7L, "private", java.util.Map.of("n", 1));

        assertTrue(frames.isEmpty());
        assertEquals(1, closed.get());
        assertEquals(0, second.connectionCount());
    }

    @Test
    void replayRevalidatesEveryFrameAndStopsWhenRevokedMidReplay() {
        AtomicBoolean valid = new AtomicBoolean(true);
        when(authentication.isValid(any())).thenAnswer(call -> valid.get());
        hub.publish(9L, "private", java.util.Map.of("n", 1));
        hub.publish(9L, "private", java.util.Map.of("n", 2));
        List<String> frames = new ArrayList<>();
        var connection = hub.register(identity(9L, "phone"), frame -> {
            frames.add(frame);
            valid.set(false);
        }, () -> {});

        assertEquals(1, hub.replay(connection, 0L));
        assertEquals(1, frames.size());
        assertTrue(connection.isClosed());
        assertEquals(1, metrics.counter("unipo.realtime.events.delivered").count());
    }

    @Test
    void revokedHandshakeContextCannotRemainRegistered() {
        when(authentication.isValid(any())).thenReturn(false);
        AtomicInteger closed = new AtomicInteger();
        var connection = hub.register(identity(1L, "cookie"), ignored -> {}, closed::incrementAndGet);

        assertTrue(connection.isClosed());
        assertEquals(0, hub.connectionCount());
        assertEquals(1, closed.get());
    }

    @Test
    void authorityFailureIsClosedWithoutStoppingOtherConnections() {
        AuthenticationContext bad = identity(7L, "bad");
        List<String> goodFrames = new ArrayList<>();
        var connection = hub.register(bad, ignored -> {}, () -> {});
        hub.register(identity(7L, "good"), goodFrames::add, () -> {});
        when(authentication.isValid(any())).thenAnswer(call -> {
            if (bad.equals(call.getArgument(0))) { throw new IllegalStateException("fixture unavailable"); }
            return true;
        });

        hub.publish(7L, "private", java.util.Map.of("n", 1));

        assertTrue(connection.isClosed());
        assertEquals(1, goodFrames.size());
        assertEquals(1, hub.connectionCount());
    }

    @Test
    void expiryStopsDeliveryEvenWhenTheValidationLeaseIsStillFresh() {
        AuthenticationContext expiring = new AuthenticationContext(identity(1L, "short").user(),
                clock.instant().plusSeconds(2), null);
        when(authentication.isValid(any())).thenAnswer(call -> clock.instant().isBefore(expiring.expiresAt()));
        List<String> frames = new ArrayList<>();
        var connection = hub.register(expiring, frames::add, () -> {});
        clock.advance(Duration.ofSeconds(2));

        hub.publish(1L, "private", java.util.Map.of("n", 1));

        assertTrue(frames.isEmpty());
        assertTrue(connection.isClosed());
    }

    @Test
    void blockedValidationCannotKeepIdleConnectionAliveOrReopenItWhenItReturns() {
        AtomicInteger closed = new AtomicInteger();
        var connection = hub.register(identity(1L, "phone"), ignored -> {}, closed::incrementAndGet);
        verification.queueTasks = true;
        clock.advance(Duration.ofSeconds(1));
        hub.revalidateConnections();
        assertEquals(1, verification.queuedCount());
        clock.advance(Duration.ofSeconds(4));

        hub.revalidateConnections();

        assertTrue(connection.isClosed());
        assertEquals(1, closed.get());
        assertEquals(0, hub.connectionCount());
        verification.runQueued();
        assertTrue(connection.isClosed());
        assertFalse(connection.send("fixture"));
        assertEquals(1, closed.get());
    }

    @Test
    void saturatedValidationQueueClosesRatherThanTrustingThePreviousLease() {
        var connection = hub.register(identity(1L, "phone"), ignored -> {}, () -> {});
        verification.reject = true;

        hub.revalidateConnections();

        assertTrue(connection.isClosed());
        assertEquals(0, hub.connectionCount());
    }

    @Test
    void idleRevocationClosesOnTheNextSweepWithoutAnyClientFrameOrBrokerEvent() {
        AtomicInteger closed = new AtomicInteger();
        var connection = hub.register(identity(1L, "idle"), ignored -> {}, closed::incrementAndGet);
        when(authentication.isValid(any())).thenReturn(false);
        clock.advance(Duration.ofSeconds(1));

        hub.revalidateConnections();

        assertTrue(connection.isClosed());
        assertEquals(1, closed.get());
        assertEquals(0, hub.connectionCount());
    }

    @Test
    void wallClockRollbackCannotExtendTheValidationLease() {
        var connection = hub.register(identity(1L, "phone"), ignored -> {}, () -> {});
        verification.queueTasks = true;
        clock.advance(Duration.ofSeconds(1));
        hub.revalidateConnections();
        clock.adjustWall(Duration.ofSeconds(-60));
        clock.advance(Duration.ofSeconds(4));

        hub.revalidateConnections();

        assertTrue(connection.isClosed());
        assertEquals(0, hub.connectionCount());
        verification.runQueued();
    }

    @Test
    void blockedWriteDoesNotBlockTheWatchdogOrCountAsSuccessfulDelivery() throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger closed = new AtomicInteger();
        var connection = hub.register(identity(1L, "slow"), ignored -> {
            writing.countDown();
            try { release.await(); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        }, closed::incrementAndGet);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var delivery = executor.submit(() -> hub.publish(1L, "private", java.util.Map.of("n", 1)));
            try {
                assertTrue(writing.await(3, TimeUnit.SECONDS));
                clock.advance(Duration.ofSeconds(3));
                hub.revalidateConnections();
                assertTrue(connection.isClosed());
                assertEquals(1, closed.get());
            } finally { release.countDown(); }
            delivery.get(3, TimeUnit.SECONDS);
        }
        assertEquals(0, metrics.counter("unipo.realtime.events.delivered").count());
    }

    @Test
    void concurrentLastRemovalAndRegistrationCannotOrphanTheNewConnection() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int attempt = 0; attempt < 200; attempt++) {
                var old = hub.register(identity(7L, "old"), ignored -> {}, () -> {});
                List<String> frames = new ArrayList<>();
                CountDownLatch start = new CountDownLatch(1);
                var remove = executor.submit(() -> { start.await(); old.close(); return null; });
                var register = executor.submit(() -> {
                    start.await();
                    return hub.register(identity(7L, "new"), frames::add, () -> {});
                });
                start.countDown();
                remove.get(3, TimeUnit.SECONDS);
                var current = register.get(3, TimeUnit.SECONDS);

                assertEquals(1, hub.connectionCount());
                hub.publish(7L, "private", java.util.Map.of("n", 1));
                assertEquals(1, frames.size());
                current.close();
            }
        }
    }

    private static java.util.Map<String, Object> messagePayload(long senderId) {
        return java.util.Map.of("message", java.util.Map.of("senderId", senderId));
    }
}
