package com.biecuoguo.security;

import com.biecuoguo.auth.api.AccountSecurityApi;
import com.biecuoguo.config.AppProperties;
import com.biecuoguo.state.SessionStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthenticationStateServiceTest {
    private final CurrentUser principal = new CurrentUser(7L, "fixture", "USER", 2L, "fixture-private-device");
    private final JwtService jwt = new JwtService(new AppProperties(null,
            new AppProperties.Jwt("unit-only-0123456789abcdef0123456789abcdef", 5, 60), null, null, null, null, null));
    private final AccountSecurityApi accounts = mock(AccountSecurityApi.class);
    private final TokenBlacklistService blacklist = mock(TokenBlacklistService.class);
    private final SessionService sessions = mock(SessionService.class);
    private final AuthenticationStateService state = new AuthenticationStateService(jwt, blacklist, accounts, sessions);

    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

    @Test void signedTokenNeedsCurrentSharedIdentityAndUsesItsRole() {
        CurrentUser staleRole = new CurrentUser(7L, "fixture", "ADMIN", 2L, principal.sessionId());
        when(accounts.validate(staleRole)).thenReturn(Optional.of(new AccountSecurityApi.ValidatedIdentity(principal, Instant.now().plusSeconds(600))));
        var context = state.authenticateAccess(jwt.createToken(staleRole)).orElseThrow();
        assertEquals("USER", context.user().role());
        assertTrue(context.expiresAt().isBefore(Instant.now().plusSeconds(301)));
        when(accounts.validate(staleRole)).thenReturn(Optional.empty());
        assertTrue(state.authenticateAccess(jwt.createToken(staleRole)).isEmpty());
    }

    @Test void unavailableAuthorityDoesNotGrantAccessOrKeepAConnectionValid() {
        when(accounts.validate(any())).thenThrow(new IllegalStateException("fixture database unavailable"));
        assertTrue(state.authenticateAccess(jwt.createToken(principal)).isEmpty());
        assertFalse(state.isValid(new AuthenticationContext(principal, Instant.now().plusSeconds(20), null)));
    }

    @Test void expiredContextAndRevokedDeviceStopAtTheBoundary() {
        assertFalse(state.isValid(new AuthenticationContext(principal, Instant.now().minusSeconds(1), null)));
        verifyNoInteractions(accounts);
        when(accounts.validate(principal)).thenReturn(Optional.empty());
        assertFalse(state.isValid(new AuthenticationContext(principal, Instant.now().plusSeconds(20), null)));
    }

    @Test void cookieValidationNeverRenewsItsLifetime() {
        String cookie = "fixture-private-cookie";
        when(sessions.peek(cookie)).thenReturn(Optional.of(new SessionStore.SessionData(7L, "USER", "fixture",
                "fixture-csrf", 2L, principal.sessionId())));
        when(accounts.validate(any())).thenReturn(Optional.of(new AccountSecurityApi.ValidatedIdentity(principal, Instant.now().plusSeconds(600))));
        AuthenticationContext context = state.authenticateCookie(cookie).orElseThrow();
        assertTrue(state.isValid(context));
        verify(sessions, never()).resolve(any());
        when(sessions.peek(cookie)).thenReturn(Optional.empty());
        assertFalse(state.isValid(context));
    }

    @Test void cookieContextMustStillBelongToTheSameDeviceAndVersion() {
        when(sessions.peek("fixture")).thenReturn(Optional.of(new SessionStore.SessionData(7L, "USER", "fixture",
                "csrf", 3L, principal.sessionId())));
        assertFalse(state.isValid(new AuthenticationContext(principal, Instant.now().plusSeconds(30), "fixture")));
    }

    @Test void explicitAuthorizationNeverFallsBackToACookie() throws Exception {
        var filter = new SessionAuthenticationFilter(sessions);
        var request = new MockHttpServletRequest("GET", "/api/messages/conversations");
        request.addHeader("Authorization", "Bearer invalid-fixture");
        request.setCookies(new Cookie("unipo_sid", "fixture-cookie"));
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                assertNull(SecurityContextHolder.getContext().getAuthentication()));
        verifyNoInteractions(sessions);
    }

    @Test void contextDebugStringsNeverContainCredentialReferences() {
        var context = new AuthenticationContext(principal, Instant.now().plusSeconds(30), "fixture-private-cookie");
        assertFalse(context.toString().contains("fixture-private-cookie"));
        assertFalse(principal.toString().contains(principal.sessionId()));
        assertFalse(principal.toString().contains(principal.email()));
    }

    @Test void unavailableCookieStateIsDistinguishedFromAnAbsentSession() throws Exception {
        when(sessions.config()).thenReturn(new AppProperties.Auth("unipo_sid", "unipo_csrf", "X-CSRF-Token", 60, false, "Lax"));
        when(sessions.resolve("fixture-cookie")).thenThrow(new IllegalStateException("fixture unavailable"));
        var request = new MockHttpServletRequest("POST", "/api/auth/logout");
        request.setCookies(new Cookie("unipo_sid", "fixture-cookie"));
        new SessionAuthenticationFilter(sessions).doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            assertEquals(Boolean.TRUE, req.getAttribute(SessionAuthenticationFilter.SESSION_UNAVAILABLE_ATTRIBUTE));
            assertNull(SecurityContextHolder.getContext().getAuthentication());
        });
    }
}
