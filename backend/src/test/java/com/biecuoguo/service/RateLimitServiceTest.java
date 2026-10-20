package com.biecuoguo.service;

import com.biecuoguo.state.MemoryRateLimitStore;
import com.biecuoguo.state.RateLimitStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitServiceTest {
    private static final Duration WINDOW = Duration.ofMinutes(5);

    @Test
    void allowsUpToLimitThenBlocks() {
        RateLimitService service = new RateLimitService(new MemoryRateLimitStore());
        for (int i = 0; i < 3; i++) {
            assertTrue(service.acquire("login|1.2.3.4", 3, WINDOW).allowed(), "第 " + (i + 1) + " 次应放行");
        }
        RateLimitService.Result blocked = service.acquire("login|1.2.3.4", 3, WINDOW);
        assertFalse(blocked.allowed());
        assertTrue(blocked.retryAfterSeconds() >= 1);
    }

    @Test
    void differentKeysDoNotShareCounters() {
        RateLimitService service = new RateLimitService(new MemoryRateLimitStore());
        assertTrue(service.acquire("a", 1, WINDOW).allowed());
        assertTrue(service.acquire("b", 1, WINDOW).allowed());
        assertFalse(service.acquire("a", 1, WINDOW).allowed());
    }

    @Test
    void storageFailureFailsOpen() {
        RateLimitStore broken = (key, window) -> {
            throw new IllegalStateException("redis down");
        };
        RateLimitService service = new RateLimitService(broken);
        // 限流是挡脚本的手段，不是安全边界：存储挂了要放行，不能把全站打成 429
        assertTrue(service.acquire("login|1.2.3.4", 1, WINDOW).allowed());
        assertEquals(0, service.acquire("login|1.2.3.4", 1, WINDOW).retryAfterSeconds());
    }
}
