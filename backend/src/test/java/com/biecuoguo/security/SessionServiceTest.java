package com.biecuoguo.security;

import com.biecuoguo.config.AppProperties;
import com.biecuoguo.state.MemorySessionStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话服务的行为钉在内存实现上；Redis 实现是同一套语义，差异只在存储。
 *
 * <p>重点不是「能存能取」，而是三件安全相关的事：
 * session id / csrf token 必须每次不同、退出即失效、踢所有设备能一次清干净。
 */
class SessionServiceTest {

    private static final AppProperties PROPS = new AppProperties(
            null, null, null, null, null,
            new AppProperties.Auth("unipo_sid", "unipo_csrf", "X-CSRF-Token", 60, false, "Lax"),
            null);

    private final SessionService service = new SessionService(new MemorySessionStore(), PROPS);

    @Test
    void createsSessionThatResolves() {
        SessionService.IssuedSession issued = service.create(42L, "USER", "a@b.com");
        var data = service.resolve(issued.sessionId()).orElseThrow();
        assertEquals(42L, data.userId());
        assertEquals("USER", data.role());
        assertEquals("a@b.com", data.email());
        assertEquals(issued.csrfToken(), data.csrfToken());
    }

    @Test
    void idsAndCsrfTokensAreRandom() {
        SessionService.IssuedSession first = service.create(1L, "USER", "a@b.com");
        SessionService.IssuedSession second = service.create(1L, "USER", "a@b.com");
        assertNotEquals(first.sessionId(), second.sessionId());
        assertNotEquals(first.csrfToken(), second.csrfToken());
        assertTrue(first.sessionId().length() >= 40, "session id 至少 32 字节随机数");
    }

    @Test
    void revokeKillsOnlyThatSession() {
        SessionService.IssuedSession keep = service.create(7L, "USER", "a@b.com");
        SessionService.IssuedSession gone = service.create(7L, "USER", "a@b.com");
        service.revoke(gone.sessionId(), 7L);

        assertTrue(service.resolve(keep.sessionId()).isPresent());
        assertFalse(service.resolve(gone.sessionId()).isPresent());
        assertTrue(service.resolve("not-a-real-session").isEmpty());
    }

    @Test
    void revokeAllKicksEveryDeviceButLeavesOtherUsersAlone() {
        SessionService.IssuedSession phone = service.create(9L, "USER", "a@b.com");
        SessionService.IssuedSession laptop = service.create(9L, "USER", "a@b.com");
        SessionService.IssuedSession other = service.create(10L, "USER", "other@b.com");

        service.revokeAllForUser(9L);

        assertFalse(service.resolve(phone.sessionId()).isPresent());
        assertFalse(service.resolve(laptop.sessionId()).isPresent());
        assertTrue(service.resolve(other.sessionId()).isPresent());
    }
}
