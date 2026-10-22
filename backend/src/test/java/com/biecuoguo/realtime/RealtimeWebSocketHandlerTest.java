package com.biecuoguo.realtime;

import com.biecuoguo.security.AuthenticationContext;
import com.biecuoguo.security.AuthenticationStateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.WebSocketMessage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.biecuoguo.realtime.RealtimeTestSupport.identity;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RealtimeWebSocketHandlerTest {
    private final AuthenticationStateService authentication = mock(AuthenticationStateService.class);
    private final RealtimeTestSupport.MutableClock clock = new RealtimeTestSupport.MutableClock();
    private final RealtimeSecurityExecutor security = new RealtimeSecurityExecutor(authentication,
            new RealtimeTestSupport.ControlledExecutor(), new RealtimeTestSupport.ControlledExecutor(), Duration.ofSeconds(1));
    private final ObjectMapper mapper = new ObjectMapper();
    private final RealtimeHub hub = new RealtimeHub(new LocalRealtimeBroker(), new MemoryRealtimeStore(),
            mapper, new SimpleMeterRegistry(), security, clock::nanoTime);
    private final RealtimeWebSocketHandler handler = new RealtimeWebSocketHandler(hub, security, mapper, clock, clock::nanoTime);
    private final AtomicBoolean valid = new AtomicBoolean(true);
    private final AuthenticationContext identity = identity(7L, "cookie-fixture");

    @BeforeEach
    void configureAuthority() {
        when(authentication.isValid(any())).thenAnswer(call -> valid.get());
        when(authentication.authenticateAccess("access-fixture")).thenReturn(Optional.of(identity));
    }

    @Test
    void bearerAuthenticationUsesTheUnifiedAuthorityAndRegistersFullContext() throws Exception {
        Socket socket = socket("phone");
        handler.afterConnectionEstablished(socket.session);
        assertTrue(socket.frames.get(0).contains("auth-required"));
        assertEquals(0, hub.connectionCount());

        authenticate(socket);

        verify(authentication).authenticateAccess("access-fixture");
        assertSame(identity, socket.attributes.get(RealtimeWebSocketHandler.ATTR_AUTH));
        assertEquals(1, hub.connectionCount());
        assertTrue(socket.frames.get(1).contains("ready"));
    }

    @Test
    void revokedCookieBetweenHandshakeAndEstablishedCannotReceiveReadyOrEvents() throws Exception {
        Socket socket = socket("browser");
        socket.attributes.put(RealtimeWebSocketHandler.ATTR_AUTH, identity);
        valid.set(false);

        handler.afterConnectionEstablished(socket.session);
        hub.publish(7L, "private", Map.of("n", 1));

        assertTrue(socket.frames.isEmpty());
        assertFalse(socket.open.get());
        assertEquals(0, hub.connectionCount());
    }

    @Test
    void everyBoundUplinkIsRejectedAfterRevocationIncludingMalformedJson() throws Exception {
        for (String payload : List.of("{\"type\":\"ping\"}", "{\"type\":\"ack\",\"seq\":1}",
                "{\"type\":\"resume\",\"lastEventId\":1}", "{\"type\":\"auth\",\"token\":\"access-fixture\"}",
                "{\"type\":\"unknown\"}", "{broken")) {
            valid.set(true);
            Socket socket = socket("socket-" + payload);
            handler.afterConnectionEstablished(socket.session);
            authenticate(socket);
            socket.frames.clear();
            valid.set(false);

            handler.handleMessage(socket.session, new TextMessage(payload));

            assertFalse(socket.open.get());
            assertTrue(socket.frames.isEmpty(), "No data or pong may be emitted for the revoked context");
            assertEquals(0, hub.connectionCount());
        }
    }

    @Test
    void unauthenticatedPingsCannotExtendTheThirtySecondDeadline() throws Exception {
        Socket socket = socket("pending");
        handler.afterConnectionEstablished(socket.session);
        clock.advance(Duration.ofSeconds(28));
        handler.handleMessage(socket.session, new TextMessage("{\"type\":\"ping\"}"));
        assertTrue(socket.open.get());
        clock.advance(Duration.ofSeconds(2));

        handler.sweepUnauthenticated();

        assertFalse(socket.open.get());
        assertEquals(0, hub.connectionCount());
        handler.handleMessage(socket.session, new TextMessage("{\"type\":\"auth\",\"token\":\"access-fixture\"}"));
        verify(authentication, never()).authenticateAccess(any());
    }

    @Test
    void authenticationCompletingAfterTheUnauthenticatedDeadlineCannotAttach() throws Exception {
        Socket socket = socket("slow-auth");
        handler.afterConnectionEstablished(socket.session);
        when(authentication.authenticateAccess("access-fixture")).thenAnswer(call -> {
            clock.advance(Duration.ofSeconds(31));
            return Optional.of(identity);
        });

        authenticate(socket);

        assertFalse(socket.open.get());
        assertEquals(0, hub.connectionCount());
        assertEquals(1, socket.frames.size());
        assertTrue(socket.frames.get(0).contains("auth-required"));
    }

    @Test
    void wallClockRollbackCannotExtendTheUnauthenticatedDeadline() throws Exception {
        Socket socket = socket("clock-rollback");
        handler.afterConnectionEstablished(socket.session);
        clock.adjustWall(Duration.ofSeconds(-60));
        clock.advance(Duration.ofSeconds(30));

        handler.sweepUnauthenticated();

        assertFalse(socket.open.get());
        assertEquals(0, hub.connectionCount());
    }

    @Test
    void rejectedInitialAuthenticationNeverRegistersOrReceivesBusinessFrames() throws Exception {
        Socket socket = socket("bad-token");
        handler.afterConnectionEstablished(socket.session);
        when(authentication.authenticateAccess("access-fixture")).thenReturn(Optional.empty());

        authenticate(socket);
        hub.publish(7L, "private", Map.of("n", 1));

        assertFalse(socket.open.get());
        assertEquals(0, hub.connectionCount());
        assertTrue(socket.frames.stream().noneMatch(frame -> frame.contains("\"type\":\"event\"")));
    }

    @Test
    void realPongAndBinaryFramesCannotBypassTheBoundIdentityCheck() throws Exception {
        for (WebSocketMessage<?> message : List.of(new PongMessage(), new BinaryMessage(new byte[]{1}))) {
            valid.set(true);
            Socket socket = socket("control-" + message.getClass().getSimpleName());
            handler.afterConnectionEstablished(socket.session);
            authenticate(socket);
            socket.frames.clear();
            valid.set(false);
            clearInvocations(authentication);

            handler.handleMessage(socket.session, message);

            verify(authentication).isValid(identity);
            verify(socket.session).close(CloseStatus.POLICY_VIOLATION);
            assertFalse(socket.open.get());
            assertTrue(socket.frames.isEmpty());
            assertEquals(0, hub.connectionCount());
        }
    }

    @Test
    void disconnectedSessionIsRemovedAndCloseIsIdempotent() throws Exception {
        Socket socket = socket("disconnect");
        handler.afterConnectionEstablished(socket.session);
        authenticate(socket);

        handler.afterConnectionClosed(socket.session, CloseStatus.NORMAL);
        handler.afterConnectionClosed(socket.session, CloseStatus.NORMAL);

        assertEquals(0, hub.connectionCount());
        verify(socket.session, atMostOnce()).close(any(CloseStatus.class));
    }

    private void authenticate(Socket socket) throws Exception {
        handler.handleMessage(socket.session, new TextMessage(mapper.writeValueAsString(
                Map.of("type", "auth", "token", "access-fixture"))));
    }

    private Socket socket(String id) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new ConcurrentHashMap<>();
        List<String> frames = new ArrayList<>();
        AtomicBoolean open = new AtomicBoolean(true);
        when(session.getId()).thenReturn(id);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenAnswer(call -> open.get());
        doAnswer(call -> { frames.add(((TextMessage) call.getArgument(0)).getPayload()); return null; })
                .when(session).sendMessage(any(TextMessage.class));
        doAnswer(call -> { open.set(false); return null; }).when(session).close(any(CloseStatus.class));
        return new Socket(session, attributes, frames, open);
    }

    private record Socket(WebSocketSession session, Map<String, Object> attributes, List<String> frames,
                          AtomicBoolean open) {}
}
