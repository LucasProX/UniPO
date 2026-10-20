package com.biecuoguo.state;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryStateStoresTest {

    @Test
    void revocationIgnoresExpiredEntries() {
        MemoryRevocationStore store = new MemoryRevocationStore();
        store.revoke("token-a", Instant.now().plusSeconds(60));
        store.revoke("token-b", Instant.now().minusSeconds(1));
        assertTrue(store.isRevoked("token-a"));
        // 已经过期的 token 本来就用不了，不该一直被记着
        assertFalse(store.isRevoked("token-b"));
        assertFalse(store.isRevoked("never-seen"));
    }

    @Test
    void presenceTracksTouchAndClear() {
        MemoryPresenceStore store = new MemoryPresenceStore();
        assertFalse(store.isOnline(5L));
        store.touch(5L);
        assertTrue(store.isOnline(5L));
        store.clear(5L);
        assertFalse(store.isOnline(5L));
    }

    @Test
    void presenceIgnoresNullUser() {
        MemoryPresenceStore store = new MemoryPresenceStore();
        store.touch(null);
        store.clear(null);
        assertFalse(store.isOnline(null));
    }

    @Test
    void rateLimitCounterIsPerKeyAndIncrements() {
        MemoryRateLimitStore store = new MemoryRateLimitStore();
        Duration window = Duration.ofMinutes(1);
        assertEquals(1, store.increment("k1|1", window));
        assertEquals(2, store.increment("k1|1", window));
        assertEquals(1, store.increment("k2|1", window));
    }
}
