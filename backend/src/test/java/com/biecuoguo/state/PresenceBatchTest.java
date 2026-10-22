package com.biecuoguo.state;

import com.biecuoguo.service.PresenceService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PresenceBatchTest {
    @Test
    void memoryBatchKeepsTheSameTouchAndClearFactsAsIndividualReads() {
        MemoryPresenceStore store = new MemoryPresenceStore();
        store.touch(17L);
        store.touch(20L);
        store.clear(20L);
        assertEquals(Set.of(17L), store.batchOnlineIds(Set.of(17L, 20L, 21L)));
        assertEquals(Set.of(), store.batchOnlineIds(Set.of()));
    }

    @Test
    void redisBatchReadsAllRequestedKeysInOneMultiGetAndNeverUsesPerPeerHasKey() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.multiGet(List.of("unipo:presence:17", "unipo:presence:20", "unipo:presence:21")))
                .thenReturn(Arrays.asList("1", null, "1"));

        assertEquals(Set.of(17L, 21L), new RedisPresenceStore(redis).batchOnlineIds(Set.of(21L, 17L, 20L)));

        verify(values).multiGet(List.of("unipo:presence:17", "unipo:presence:20", "unipo:presence:21"));
        verify(redis, never()).hasKey(anyString());
    }

    @Test
    void emptyRedisBatchDoesNotContactTheBackend() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        assertEquals(Set.of(), new RedisPresenceStore(redis).batchOnlineIds(Set.of()));
        verifyNoInteractions(redis);
    }

    @Test
    void informationalPresenceFailureDegradesOfflineAfterTryingTheActualStore() {
        PresenceStore store = mock(PresenceStore.class);
        when(store.batchOnlineIds(Set.of(17L, 20L))).thenThrow(new IllegalStateException("unavailable"));
        when(store.isOnline(17L)).thenThrow(new IllegalStateException("unavailable"));
        PresenceService service = new PresenceService(store);
        assertEquals(Set.of(), service.batchOnlineIds(Set.of(17L, 20L)));
        assertFalse(service.isOnline(17L));
        verify(store).batchOnlineIds(Set.of(17L, 20L));
        verify(store).isOnline(17L);
    }
}
