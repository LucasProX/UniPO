package com.biecuoguo.config;

import com.biecuoguo.realtime.RealtimeHub;
import com.biecuoguo.realtime.RealtimeWebSocketHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** An independent watchdog: slow authority queries and socket writes never run here. */
@Component
public final class RealtimeSecuritySchedule {
    private final RealtimeHub hub;
    private final RealtimeWebSocketHandler handler;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "unipo-realtime-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    public RealtimeSecuritySchedule(RealtimeHub hub, RealtimeWebSocketHandler handler) {
        this.hub = hub;
        this.handler = handler;
    }

    @PostConstruct
    public void start() {
        watchdog.scheduleAtFixedRate(this::sweep, 1, 1, TimeUnit.SECONDS);
    }

    public void sweep() {
        try { hub.revalidateConnections(); }
        catch (RuntimeException ignored) { /* A future tick must still run. */ }
        try { handler.sweepUnauthenticated(); }
        catch (RuntimeException ignored) { /* A future tick must still run. */ }
    }

    @PreDestroy
    public void stop() { watchdog.shutdownNow(); }
}
