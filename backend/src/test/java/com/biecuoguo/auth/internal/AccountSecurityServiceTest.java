package com.biecuoguo.auth.internal;

import com.biecuoguo.auth.api.AccountSecurityApi;
import com.biecuoguo.config.AppProperties;
import com.biecuoguo.domain.User;
import com.biecuoguo.security.CurrentUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.InsufficientAuthenticationException;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountSecurityServiceTest {
    private static final long USER = 7L;
    private static final long VERSION = 3L;
    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
    private static final long EXPIRY = NOW.plus(Duration.ofMinutes(17)).toEpochMilli();
    private final DeviceSessionMapper mapper = mock(DeviceSessionMapper.class);
    private final AccountSecurityService service = new AccountSecurityService(mapper, properties(17),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void createUsesCapturedVersionAndConfiguredAbsoluteLifetime() {
        when(mapper.insertIfCurrent(anyString(), eq(USER), eq(VERSION), anyLong(), anyLong())).thenReturn(1);
        when(mapper.findValid(anyString(), eq(USER), eq(VERSION), anyLong())).thenAnswer(call ->
                new DeviceSessionRecord(USER, "current@example.invalid", "USER", VERSION, call.getArgument(0), EXPIRY));

        AccountSecurityApi.ValidatedIdentity result = service.createSession(
                new CurrentUser(USER, "captured@example.invalid", "ADMIN", VERSION, null));

        assertEquals(USER, result.user().id());
        assertEquals(VERSION, result.user().authVersion());
        assertEquals("USER", result.user().role());
        assertEquals("current@example.invalid", result.user().email());
        assertEquals(Instant.ofEpochMilli(EXPIRY), result.expiresAt());
        assertEquals(32, Base64.getUrlDecoder().decode(result.user().sessionId()).length);
        assertEquals(43, result.user().sessionId().length());
        verify(mapper).insertIfCurrent(result.user().sessionId(), USER, VERSION, NOW.toEpochMilli(), EXPIRY);
    }

    @Test
    void independentLoginsGetIndependentDeviceSessions() {
        Set<String> ids = new HashSet<>();
        when(mapper.insertIfCurrent(anyString(), eq(USER), eq(VERSION), anyLong(), anyLong())).thenReturn(1);
        when(mapper.findValid(anyString(), eq(USER), eq(VERSION), anyLong())).thenAnswer(call ->
                new DeviceSessionRecord(USER, "user@example.invalid", "USER", VERSION, call.getArgument(0), EXPIRY));

        for (int i = 0; i < 16; i++) {
            assertTrue(ids.add(service.createSession(expectedUser()).user().sessionId()));
        }
    }

    @Test
    void rejectedAtomicInsertNeverUpgradesToTheNewUserVersion() {
        when(mapper.insertIfCurrent(anyString(), eq(USER), eq(VERSION), anyLong(), anyLong())).thenReturn(0);

        assertThrows(InsufficientAuthenticationException.class, () -> service.createSession(expectedUser()));

        verify(mapper).insertIfCurrent(anyString(), eq(USER), eq(VERSION), eq(NOW.toEpochMilli()), eq(EXPIRY));
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void createDoesNotPublishAnIdentityIfTheAuthorityCannotValidateIt() {
        when(mapper.insertIfCurrent(anyString(), eq(USER), eq(VERSION), anyLong(), anyLong())).thenReturn(1);
        assertThrows(InsufficientAuthenticationException.class, () -> service.createSession(expectedUser()));
    }

    @Test
    void nonVersionedInternalPrincipalsCannotCreateCredentialSessions() {
        for (CurrentUser invalid : new CurrentUser[]{null, new CurrentUser(USER, null, "USER"),
                new CurrentUser(null, null, null, VERSION, null), new CurrentUser(-1L, null, null, VERSION, null),
                new CurrentUser(USER, null, null, -1L, null)}) {
            assertThrows(InsufficientAuthenticationException.class, () -> service.createSession(invalid));
        }
        verifyNoInteractions(mapper);
    }

    @Test
    void everyValidationRechecksAuthorityInsteadOfCachingTheFirstSuccess() {
        String sid = randomSessionId();
        CurrentUser credential = new CurrentUser(USER, "old@example.invalid", "ADMIN", VERSION, sid);
        when(mapper.findValid(sid, USER, VERSION, NOW.toEpochMilli()))
                .thenReturn(new DeviceSessionRecord(USER, "latest@example.invalid", "USER", VERSION, sid, EXPIRY))
                .thenReturn(null);

        AccountSecurityApi.ValidatedIdentity valid = service.validate(credential).orElseThrow();
        assertEquals("USER", valid.user().role());
        assertEquals("latest@example.invalid", valid.user().email());
        assertEquals(sid, valid.user().sessionId());
        assertEquals(VERSION, valid.user().authVersion());
        assertEquals(Instant.ofEpochMilli(EXPIRY), valid.expiresAt());
        assertTrue(service.validate(credential).isEmpty());
        verify(mapper, times(2)).findValid(sid, USER, VERSION, NOW.toEpochMilli());
    }

    @Test
    void missingVersionOrMalformedSessionCannotReachTheAuthorityQuery() {
        for (CurrentUser invalid : new CurrentUser[]{null, new CurrentUser(USER, null, "USER"),
                new CurrentUser(USER, null, "USER", VERSION, null),
                new CurrentUser(USER, null, "USER", VERSION, ""),
                new CurrentUser(USER, null, "USER", VERSION, "invalid"),
                new CurrentUser(USER, null, "USER", VERSION, " ".repeat(43))}) {
            assertTrue(service.validate(invalid).isEmpty());
        }
        verifyNoInteractions(mapper);
    }

    @Test
    void authorityOutageCannotFallBackToAPreviouslyValidIdentity() {
        String sid = randomSessionId();
        CurrentUser credential = new CurrentUser(USER, null, "USER", VERSION, sid);
        when(mapper.findValid(sid, USER, VERSION, NOW.toEpochMilli()))
                .thenReturn(new DeviceSessionRecord(USER, "user@example.invalid", "USER", VERSION, sid, EXPIRY))
                .thenThrow(new DataAccessResourceFailureException("isolated authority failure"));

        assertTrue(service.validate(credential).isPresent());
        assertThrows(DataAccessResourceFailureException.class, () -> service.validate(credential));
    }

    @Test
    void currentDeviceRevocationAlwaysIncludesTheOwner() {
        String sid = randomSessionId();
        service.revokeSession(sid, USER);
        verify(mapper).revokeOne(sid, USER, NOW.toEpochMilli());
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void invalidRevocationTargetsDoNotWrite() {
        service.revokeSession(null, USER);
        service.revokeSession("", USER);
        service.revokeSession(randomSessionId(), -1L);
        service.revokeAll(0L);
        verifyNoInteractions(mapper);
    }

    @Test
    void allDeviceRevocationAdvancesTheVersionBeforeMarkingOldSessions() {
        when(mapper.advanceUserVersion(USER)).thenReturn(1);

        service.revokeAll(USER);

        InOrder order = inOrder(mapper);
        order.verify(mapper).advanceUserVersion(USER);
        order.verify(mapper).revokeOlderVersions(USER, NOW.toEpochMilli());
        order.verifyNoMoreInteractions();
    }

    @Test
    void missingUserRevocationDoesNotPretendThereAreDeviceRowsToUpdate() {
        when(mapper.advanceUserVersion(USER)).thenReturn(0);
        service.revokeAll(USER);
        verify(mapper).advanceUserVersion(USER);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void revocationWriteErrorsPropagateInsteadOfReportingSuccess() {
        when(mapper.advanceUserVersion(USER)).thenReturn(1);
        doThrow(new DataAccessResourceFailureException("isolated revocation failure"))
                .when(mapper).revokeOlderVersions(USER, NOW.toEpochMilli());

        assertThrows(DataAccessResourceFailureException.class, () -> service.revokeAll(USER));
    }

    @Test
    void nonPositiveOrOverflowingLifetimeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AccountSecurityService(mapper, properties(0)));
        assertThrows(IllegalArgumentException.class, () -> new AccountSecurityService(mapper, properties(-1)));
        assertThrows(ArithmeticException.class, () -> new AccountSecurityService(mapper, properties(Long.MAX_VALUE)));
    }

    @Test
    void internalVersionDefaultsToZeroAndIsNotSerialized() throws Exception {
        User user = new User();
        assertEquals(0L, user.getAuthVersion());
        user.setAuthVersion(9L);
        user.setPasswordHash("not-a-real-credential");
        var json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsBytes(user));
        assertFalse(json.has("authVersion"));
        assertFalse(json.has("passwordHash"));
    }

    private static CurrentUser expectedUser() {
        return new CurrentUser(USER, "user@example.invalid", "USER", VERSION, null);
    }

    private static AppProperties properties(long refreshMinutes) {
        return new AppProperties(null, new AppProperties.Jwt(null, 120, refreshMinutes), null, null, null, null, null);
    }

    private static String randomSessionId() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
