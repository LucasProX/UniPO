package com.biecuoguo.state;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内会话存储。单实例开发用；重启即丢，多实例不共享。
 */
@Component
@ConditionalOnProperty(name = "app.state.backend", havingValue = "memory", matchIfMissing = true)
public class MemorySessionStore implements SessionStore {

    private record Entry(SessionData data, Instant expiresAt) {
    }

    private final ConcurrentHashMap<String, Entry> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Set<String>> byUser = new ConcurrentHashMap<>();

    @Override
    public synchronized void save(String sessionId, SessionData data, Duration ttl) {
        sessions.put(sessionId, new Entry(data, Instant.now().plus(ttl)));
        byUser.computeIfAbsent(data.userId(), k -> ConcurrentHashMap.newKeySet()).add(sessionId);
    }

    @Override
    public synchronized Optional<SessionData> loadAndTouch(String sessionId, Duration ttl) {
        Optional<SessionData> data = load(sessionId);
        data.ifPresent(value -> sessions.put(sessionId, new Entry(value, Instant.now().plus(ttl))));
        return data;
    }

    @Override
    public synchronized Optional<SessionData> load(String sessionId) {
        Entry entry = sessions.get(sessionId);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAt().isBefore(Instant.now())) {
            sessions.remove(sessionId);
            Set<String> owned = byUser.get(entry.data().userId());
            if (owned != null) { owned.remove(sessionId); }
            return Optional.empty();
        }
        return Optional.of(entry.data());
    }

    @Override
    public synchronized void delete(String sessionId, long userId) {
        sessions.remove(sessionId);
        Set<String> set = byUser.get(userId);
        if (set != null) {
            set.remove(sessionId);
        }
    }

    @Override
    public synchronized void deleteAllForUser(long userId) {
        Set<String> set = byUser.remove(userId);
        if (set != null) {
            set.forEach(sessions::remove);
        }
    }
}
