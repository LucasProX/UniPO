package com.biecuoguo.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 进程内的 SSE 推送中心（D-6）。一个用户可能开着多个标签页，所以是 userId -> 多个 emitter。
 *
 * <p>为什么不用 WebSocket：这里只有「服务端单向推消息」一种需求，SSE 走普通 HTTP，
 * 复用现有的 JWT 拦截、限流、requestId 日志，运维上不需要多开一条 upgrade 通道。
 * 浏览器侧的 {@code EventSource} 不能带 Authorization 头，所以前端用 fetch + ReadableStream 读流，
 * 见 {@code frontend/src/lib/realtime.ts}。
 *
 * <p>**限制（刻意写在代码里）**：emitter 存在本进程内存里，多实例部署时 A 实例发的消息推不到
 * 连在 B 实例上的客户端。这跟 {@code STATE_BACKEND=memory} 是同一类问题，等真要多副本时再把
 * publish 走 Redis 频道（Q-4 的 redis 基础设施已经在了）。单实例 + 前端轮询兜底是当前状态。
 */
@Service
public class RealtimeHub {
    private static final Logger log = LoggerFactory.getLogger(RealtimeHub.class);

    /** 连接最长存活 30 分钟，到点由容器关闭连接，前端会自动重连（避免中间代理掐掉长连接后客户端毫无察觉）。 */
    private static final long TIMEOUT_MS = 30L * 60L * 1000L;
    /** 20 秒一次注释心跳，防止 nginx/Cloudflare 这类代理把空闲连接当死链回收。 */
    private static final long HEARTBEAT_MS = 20_000L;

    private final Map<Long, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "realtime-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public RealtimeHub() {
        heartbeat.scheduleAtFixedRate(this::pingAll, HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS);
    }

    /** 注册一条连接；onCompletion/onTimeout/onError 三个回调都要清理，少一个就会漏掉 emitter。 */
    public SseEmitter subscribe(Long userId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitters.computeIfAbsent(userId, key -> ConcurrentHashMap.newKeySet()).add(emitter);
        Runnable cleanup = () -> remove(userId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());
        try {
            // 先发一条 ready：让前端立刻知道「连上了」，也让代理看到首字节。
            emitter.send(SseEmitter.event().name("ready").data(Map.of("userId", userId)));
        } catch (IOException error) {
            cleanup.run();
        }
        return emitter;
    }

    /** 推给某个用户的全部连接。没有在线连接就直接返回，不攒离线消息（离线靠前端拉未读数）。 */
    public void publish(Long userId, String event, Object data) {
        Set<SseEmitter> targets = emitters.get(userId);
        if (targets == null || targets.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : targets) {
            try {
                emitter.send(SseEmitter.event().name(event).data(data));
            } catch (Exception error) {
                // 客户端断开是常态（切页、断网），不算错误路径，直接摘掉。
                remove(userId, emitter);
            }
        }
    }

    /** 当前在线连接数，测试和运维排查用。 */
    public int connectionCount() {
        return emitters.values().stream().mapToInt(Set::size).sum();
    }

    void remove(Long userId, SseEmitter emitter) {
        Set<SseEmitter> targets = emitters.get(userId);
        if (targets == null) {
            return;
        }
        targets.remove(emitter);
        if (targets.isEmpty()) {
            emitters.remove(userId);
        }
    }

    private void pingAll() {
        if (emitters.isEmpty()) {
            return;
        }
        emitters.forEach((userId, targets) -> {
            for (SseEmitter emitter : targets) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (Exception error) {
                    remove(userId, emitter);
                    log.debug("realtime heartbeat drop user={}", userId);
                }
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        heartbeat.shutdownNow();
        emitters.values().forEach(targets -> targets.forEach(SseEmitter::complete));
        emitters.clear();
    }
}
