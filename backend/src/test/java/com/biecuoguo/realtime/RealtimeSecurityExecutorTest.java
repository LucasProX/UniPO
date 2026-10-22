package com.biecuoguo.realtime;

import com.biecuoguo.security.AuthenticationStateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.biecuoguo.realtime.RealtimeTestSupport.identity;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RealtimeSecurityExecutorTest {
    @Test
    void healthyFanOutCanQueueThreeHundredIdleConnectionsWithoutSpuriousClosures() throws Exception {
        AuthenticationStateService authentication = mock(AuthenticationStateService.class);
        when(authentication.isValid(any())).thenReturn(true);
        RealtimeSecurityExecutor security = new RealtimeSecurityExecutor(authentication);
        RealtimeTestSupport.MutableClock clock = new RealtimeTestSupport.MutableClock();
        RealtimeHub hub = new RealtimeHub(new LocalRealtimeBroker(), new MemoryRealtimeStore(),
                new ObjectMapper(), new SimpleMeterRegistry(), security, clock::nanoTime);
        List<RealtimeHub.RealtimeConnection> connections = new ArrayList<>();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch checked = new CountDownLatch(300);
        try {
            for (int index = 0; index < 300; index++) {
                connections.add(hub.register(identity(1L, "phone-" + index), ignored -> {}, () -> {}));
            }
            when(authentication.isValid(any())).thenAnswer(call -> {
                release.await();
                checked.countDown();
                return true;
            });

            hub.revalidateConnections();

            assertEquals(300, hub.connectionCount());
            release.countDown();
            assertTrue(checked.await(5, TimeUnit.SECONDS));
            assertEquals(300, hub.connectionCount());
        } finally {
            release.countDown();
            connections.forEach(RealtimeHub.RealtimeConnection::close);
            security.destroy();
        }
    }

    @Test
    void blockedAuthorityHasBoundedCallTimeAndQueueSaturationFailsClosed() throws Exception {
        AuthenticationStateService authentication = mock(AuthenticationStateService.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        when(authentication.isValid(any())).thenAnswer(call -> {
            entered.countDown();
            release.await();
            return true;
        });
        var verification = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.AbortPolicy());
        var security = new RealtimeSecurityExecutor(authentication, verification,
                new RealtimeTestSupport.ControlledExecutor(), Duration.ofMillis(20));
        try {
            assertTrue(security.validateAsync(identity(1L, "blocked"), ignored -> completed.countDown()));
            assertTrue(entered.await(3, TimeUnit.SECONDS));

            assertFalse(security.isValidNow(identity(1L, "queued")));
            assertFalse(security.validateAsync(identity(1L, "rejected"), ignored -> {}));

            verify(authentication, times(1)).isValid(any());
        } finally {
            release.countDown();
            try { assertTrue(completed.await(3, TimeUnit.SECONDS)); }
            finally { security.destroy(); }
        }
    }
}
