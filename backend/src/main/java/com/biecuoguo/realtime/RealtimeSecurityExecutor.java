package com.biecuoguo.realtime;

import com.biecuoguo.security.AuthenticationContext;
import com.biecuoguo.security.AuthenticationStateService;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Bounds authority lookups independently of the WebSocket watchdog. No credential is logged. */
@Component
public class RealtimeSecurityExecutor implements DisposableBean {
    private final AuthenticationStateService authentication;
    private final ExecutorService verification;
    private final ExecutorService transportClose;
    private final Duration timeout;

    @Autowired
    public RealtimeSecurityExecutor(AuthenticationStateService authentication) {
        this(authentication, verificationPool(), Executors.newVirtualThreadPerTaskExecutor(), Duration.ofSeconds(1));
    }

    RealtimeSecurityExecutor(AuthenticationStateService authentication, ExecutorService verification,
                             ExecutorService transportClose, Duration timeout) {
        this.authentication = authentication;
        this.verification = verification;
        this.transportClose = transportClose;
        this.timeout = timeout;
    }

    public Optional<AuthenticationContext> authenticateAccess(String token) {
        return call(() -> authentication.authenticateAccess(token), Optional.empty());
    }

    public Optional<AuthenticationContext> authenticateCookie(String sessionId) {
        return call(() -> authentication.authenticateCookie(sessionId), Optional.empty());
    }

    boolean isValidNow(AuthenticationContext context) {
        return call(() -> authentication.isValid(context), false);
    }

    boolean validateAsync(AuthenticationContext context, Consumer<Boolean> completion) {
        try {
            verification.execute(() -> {
                boolean valid;
                try { valid = authentication.isValid(context); }
                catch (RuntimeException failure) { valid = false; }
                completion.accept(valid);
            });
            return true;
        } catch (RejectedExecutionException failure) {
            return false;
        }
    }

    void closeTransport(Runnable close) {
        try {
            transportClose.execute(() -> {
                try { close.run(); }
                catch (RuntimeException ignored) { /* Logical removal has already completed. */ }
            });
        } catch (RejectedExecutionException ignored) {
            // During shutdown the container also owns transport cleanup.
        }
    }

    private <T> T call(Callable<T> operation, T denied) {
        Future<T> future;
        try { future = verification.submit(operation); }
        catch (RejectedExecutionException failure) { return denied; }
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return denied;
        } catch (Exception failure) {
            future.cancel(true);
            return denied;
        }
    }

    private static ExecutorService verificationPool() {
        AtomicInteger next = new AtomicInteger();
        return new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(4096), task -> {
                    Thread thread = new Thread(task, "unipo-realtime-auth-" + next.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public void destroy() {
        verification.shutdownNow();
        transportClose.shutdownNow();
    }
}
