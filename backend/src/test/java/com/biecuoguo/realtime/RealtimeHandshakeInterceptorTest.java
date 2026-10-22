package com.biecuoguo.realtime;

import com.biecuoguo.security.AuthenticationStateService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.biecuoguo.realtime.RealtimeTestSupport.identity;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RealtimeHandshakeInterceptorTest {
    private final AuthenticationStateService authentication = mock(AuthenticationStateService.class);
    private final RealtimeSecurityExecutor security = new RealtimeSecurityExecutor(authentication,
            new RealtimeTestSupport.ControlledExecutor(), new RealtimeTestSupport.ControlledExecutor(), Duration.ofSeconds(1));
    private final RealtimeHandshakeInterceptor interceptor = new RealtimeHandshakeInterceptor(security, "unipo_sid");

    @Test
    void cookieHandshakeKeepsTheWholeContextForLaterRevalidation() {
        var identity = identity(7L, "cookie-fixture");
        when(authentication.authenticateCookie("cookie-fixture")).thenReturn(Optional.of(identity));
        Map<String, Object> attributes = new HashMap<>();

        assertTrue(handshake(attributes, new Cookie("unipo_sid", "cookie-fixture")));

        assertSame(identity, attributes.get(RealtimeWebSocketHandler.ATTR_AUTH));
        assertEquals(7L, attributes.get(RealtimeWebSocketHandler.ATTR_USER));
        verify(authentication).authenticateCookie("cookie-fixture");
    }

    @Test
    void missingCookieAllowsAppAuthenticationButCreatesNoIdentity() {
        Map<String, Object> attributes = new HashMap<>();

        assertTrue(handshake(attributes));

        assertTrue(attributes.isEmpty());
        verifyNoInteractions(authentication);
    }

    @Test
    void rejectedOrUnavailableCookieCannotAttachAnIdentity() {
        when(authentication.authenticateCookie("revoked")).thenReturn(Optional.empty());
        Map<String, Object> rejected = new HashMap<>();
        assertTrue(handshake(rejected, new Cookie("unipo_sid", "revoked")));
        assertTrue(rejected.isEmpty());

        when(authentication.authenticateCookie("unavailable")).thenThrow(new IllegalStateException("fixture"));
        Map<String, Object> unavailable = new HashMap<>();
        assertTrue(handshake(unavailable, new Cookie("unipo_sid", "unavailable")));
        assertTrue(unavailable.isEmpty());
    }

    private boolean handshake(Map<String, Object> attributes, Cookie... cookies) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(cookies);
        return interceptor.beforeHandshake(new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(new MockHttpServletResponse()), mock(WebSocketHandler.class), attributes);
    }
}
