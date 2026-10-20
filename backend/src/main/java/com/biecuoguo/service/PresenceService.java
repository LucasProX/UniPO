package com.biecuoguo.service;

import com.biecuoguo.state.PresenceStore;
import org.springframework.stereotype.Service;

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
        return store.isOnline(userId);
    }
}
