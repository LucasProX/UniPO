package com.biecuoguo.service;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PresenceService {
    private static final Duration ONLINE_WINDOW = Duration.ofSeconds(25);

    private final Map<Long, Instant> lastSeen = new ConcurrentHashMap<>();

    public void markOnline(Long userId) {
        if (userId != null) {
            lastSeen.put(userId, Instant.now());
        }
    }

    public void markOffline(Long userId) {
        if (userId != null) {
            lastSeen.remove(userId);
        }
    }

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
}
