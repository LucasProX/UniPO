package com.biecuoguo.realtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单机续传缓存：每个用户最近 200 条事件。重启即丢——单机部署本来就没有跨实例语义。
 */
@Component
@ConditionalOnProperty(name = "app.state.backend", havingValue = "memory", matchIfMissing = true)
public class MemoryRealtimeStore implements RealtimeStore {

    private static final int MAX_PER_USER = 200;

    private final AtomicLong seq = new AtomicLong();
    private final Map<Long, Deque<RealtimeEvent>> events = new ConcurrentHashMap<>();

    @Override
    public long nextSeq() {
        return seq.incrementAndGet();
    }

    @Override
    public void append(Long userId, RealtimeEvent event) {
        Deque<RealtimeEvent> deque = events.computeIfAbsent(userId, key -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(event);
            while (deque.size() > MAX_PER_USER) {
                deque.removeFirst();
            }
        }
    }

    @Override
    public List<RealtimeEvent> after(Long userId, long afterSeq, int limit) {
        Deque<RealtimeEvent> deque = events.get(userId);
        if (deque == null) {
            return List.of();
        }
        List<RealtimeEvent> result = new ArrayList<>();
        synchronized (deque) {
            for (RealtimeEvent event : deque) {
                if (event.seq() > afterSeq) {
                    result.add(event);
                    if (result.size() >= limit) {
                        break;
                    }
                }
            }
        }
        return result;
    }
}
