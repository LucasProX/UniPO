package com.biecuoguo.service;

import com.biecuoguo.state.PresenceStore;
import org.springframework.stereotype.Service;

import java.util.Set;

/** 在线状态：心跳来自 {@code POST /api/auth/heartbeat}，判定窗口见 {@link PresenceStore#ONLINE_WINDOW}。 */
@Service
public class PresenceService {
    private final PresenceStore store;

    public PresenceService(PresenceStore store) {
        this.store = store;
    }

    public void markOnline(Long userId) {
        store.touch(userId);
    }

    public void markOffline(Long userId) {
        store.clear(userId);
    }

    public boolean isOnline(Long userId) {
        try {
            return store.isOnline(userId);
        } catch (RuntimeException unavailable) {
            // Presence is informational. An unavailable store must not break authenticated business reads.
            return false;
        }
    }

    public Set<Long> batchOnlineIds(Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) { return Set.of(); }
        try {
            return store.batchOnlineIds(userIds);
        } catch (RuntimeException unavailable) {
            return Set.of();
        }
    }
}
