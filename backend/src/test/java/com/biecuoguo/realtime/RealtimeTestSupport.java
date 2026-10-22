package com.biecuoguo.realtime;

import com.biecuoguo.security.AuthenticationContext;
import com.biecuoguo.security.CurrentUser;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

final class RealtimeTestSupport {
    private RealtimeTestSupport() {}

    static AuthenticationContext identity(long userId, String credential) {
        return new AuthenticationContext(new CurrentUser(userId, "fixture", "USER", 1L, "device-" + credential),
                Instant.parse("2030-01-01T00:00:00Z"), credential);
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-21T00:00:00Z"));
        private final AtomicLong ticks = new AtomicLong();
        void advance(Duration duration) { now.updateAndGet(value -> value.plus(duration)); ticks.addAndGet(duration.toNanos()); }
        void adjustWall(Duration duration) { now.updateAndGet(value -> value.plus(duration)); }
        long nanoTime() { return ticks.get(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return now.get(); }
    }

    static final class ControlledExecutor extends AbstractExecutorService {
        private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
        volatile boolean queueTasks;
        volatile boolean reject;
        private volatile boolean shutdown;

        @Override public void execute(Runnable task) {
            if (reject || shutdown) { throw new RejectedExecutionException(); }
            if (queueTasks) { tasks.add(task); } else { task.run(); }
        }

        void runQueued() {
            Runnable task;
            while ((task = tasks.poll()) != null) { task.run(); }
        }

        int queuedCount() { return tasks.size(); }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
    }
}
