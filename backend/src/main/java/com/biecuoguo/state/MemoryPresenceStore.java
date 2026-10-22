package com.biecuoguo.state;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(name = "app.state.backend", havingValue = "memory", matchIfMissing = true)
public class MemoryPresenceStore implements PresenceStore {
    private final Map<Long, Instant> lastSeen = new ConcurrentHashMap<>();

    @Override
    public void touch(Long userId) {
        if (userId != null) {
            lastSeen.put(userId, Instant.now());
        }
    }

    @Override
    public void clear(Long userId) {
        if (userId != null) {
            lastSeen.remove(userId);
        }
    }

    @Override
    public boolean isOnline(Long userId) {
        if (userId == null) {
            return false;
        }
        Instant seenAt = lastSeen.get(userId);
        if (seenAt == null) {
            return false;
        }
        boolean online = seenAt.plus(ONLINE_WINDOW).isAfter(Instant.now());
        if (!online) {
            lastSeen.remove(userId, seenAt);
        }
        return online;
    }

    @Override
    public Set<Long> batchOnlineIds(Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) { return Set.of(); }
        return userIds.stream().filter(Objects::nonNull).filter(this::isOnline).collect(Collectors.toUnmodifiableSet());
    }
}
