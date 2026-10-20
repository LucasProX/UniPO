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
    public void save(String sessionId, SessionData data, Duration ttl) {
        sessions.put(sessionId, new Entry(data, Instant.now().plus(ttl)));
        byUser.computeIfAbsent(data.userId(), k -> ConcurrentHashMap.newKeySet()).add(sessionId);
    }

    @Override
    public Optional<SessionData> loadAndTouch(String sessionId, Duration ttl) {
        Entry entry = sessions.get(sessionId);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAt().isBefore(Instant.now())) {
            sessions.remove(sessionId);
            byUser.getOrDefault(entry.data().userId(), Set.of()).remove(sessionId);
            return Optional.empty();
        }
        sessions.put(sessionId, new Entry(entry.data(), Instant.now().plus(ttl)));
        return Optional.of(entry.data());
    }

    @Override
    public void delete(String sessionId, long userId) {
        sessions.remove(sessionId);
        Set<String> set = byUser.get(userId);
        if (set != null) {
            set.remove(sessionId);
        }
    }

    @Override
    public void deleteAllForUser(long userId) {
        Set<String> set = byUser.remove(userId);
        if (set != null) {
            set.forEach(sessions::remove);
        }
    }
}
