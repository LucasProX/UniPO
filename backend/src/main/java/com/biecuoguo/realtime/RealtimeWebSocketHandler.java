package com.biecuoguo.realtime;

import com.biecuoguo.security.AuthenticationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * 实时通道的 WebSocket 处理器。
 *
 * <p>鉴权有两条路：
 * <ol>
 *   <li><b>浏览器</b>：握手时自动带 httpOnly 会话 Cookie，{@link RealtimeHandshakeInterceptor} 直接解析出 userId；</li>
 *   <li><b>App / 脚本</b>：握手不带 Cookie，连上后先发 {@code {"type":"auth","token":"..."}}，
 *       服务端校验 JWT 与撤销名单后再绑定用户。</li>
 * </ol>
 *
 * <p>协议（JSON 文本帧）：
 * <pre>
 * 客户端 -> 服务端: auth {token} | resume {lastEventId} | ping | ack {seq}
 * 服务端 -> 客户端: ready {userId} | event {event,seq,ts,payload} | pong {ts} | error {code}
 * </pre>
 *
 * <p>未认证连接有固定的 30 秒截止时间；ping 不会延长它。
 */
@Component
public class RealtimeWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RealtimeWebSocketHandler.class);
    static final String ATTR_USER = "unipo.realtime.userId";
    static final String ATTR_AUTH = "unipo.realtime.authentication";
    static final String ATTR_CONN = "unipo.realtime.connection";
    static final Duration AUTHENTICATION_LIMIT = Duration.ofSeconds(30);

    private final RealtimeHub hub;
    private final RealtimeSecurityExecutor security;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final LongSupplier ticker;
    private final Map<String, PendingConnection> pendingConnections = new ConcurrentHashMap<>();

    @Autowired
    public RealtimeWebSocketHandler(RealtimeHub hub, RealtimeSecurityExecutor security, ObjectMapper objectMapper) {
        this(hub, security, objectMapper, Clock.systemUTC(), System::nanoTime);
    }

    RealtimeWebSocketHandler(RealtimeHub hub, RealtimeSecurityExecutor security, ObjectMapper objectMapper,
                             Clock clock, LongSupplier ticker) {
        this.hub = hub;
        this.security = security;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.ticker = ticker;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // Reserve one watchdog interval so even a connection established just after a tick closes within 30s.
        long deadline = ticker.getAsLong() + AUTHENTICATION_LIMIT.minusSeconds(1).toNanos();
        pendingConnections.put(session.getId(), new PendingConnection(session, deadline));
        Object identity = session.getAttributes().get(ATTR_AUTH);
        if (identity instanceof AuthenticationContext context) {
            attach(session, context);
            return;
        }
        send(session, "{\"type\":\"auth-required\"}");
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
        Object attached = session.getAttributes().get(ATTR_CONN);
        if (attached instanceof RealtimeHub.RealtimeConnection connection) {
            if (!session.isOpen()) { connection.close(); return; }
            if (!connection.requireValid()) { return; }
        } else if (!pendingIsActive(session)) {
            closePending(session);
            return;
        }
        super.handleMessage(session, message);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode node;
        try {
            node = objectMapper.readTree(message.getPayload());
            if (node == null || !node.isObject()) { throw new IllegalArgumentException("Expected JSON object"); }
        } catch (Exception e) {
            sendError(session, "VALIDATION_FAILED", "消息不是合法 JSON");
            return;
        }
        String type = node.path("type").asText("");
        switch (type) {
            case "auth" -> handleAuth(session, node.path("token").asText(""));
            case "resume" -> handleResume(session, node.path("lastEventId").asLong(0L));
            case "ping" -> send(session, "{\"type\":\"pong\",\"ts\":" + clock.millis() + "}");
            case "ack" -> {
                // 客户端确认已渲染。当前不做服务端重发，只记 debug 便于排查投递问题。
                log.debug("realtime ack user={} seq={}", session.getAttributes().get(ATTR_USER),
                        node.path("seq").asLong(-1L));
            }
            default -> sendError(session, "VALIDATION_FAILED", "未知消息类型");
        }
    }

    private void handleAuth(WebSocketSession session, String token) throws Exception {
        if (session.getAttributes().get(ATTR_USER) != null) {
            return;
        }
        AuthenticationContext identity = security.authenticateAccess(token).orElse(null);
        if (identity == null) {
            sendError(session, "AUTH_UNAUTHENTICATED", "请先登录");
            closePending(session);
            return;
        }
        attach(session, identity);
    }

    private void handleResume(WebSocketSession session, long lastEventId) throws Exception {
        Object attached = session.getAttributes().get(ATTR_CONN);
        if (!(attached instanceof RealtimeHub.RealtimeConnection connection)) {
            sendError(session, "AUTH_UNAUTHENTICATED", "请先登录");
            return;
        }
        if (lastEventId > 0) {
            hub.replay(connection, lastEventId);
        }
    }

    /** 绑定用户并注册连接。重复调用是幂等的（同一 session 只注册一次）。 */
    private void attach(WebSocketSession session, AuthenticationContext identity) throws Exception {
        if (session.getAttributes().get(ATTR_CONN) != null) {
            return;
        }
        PendingConnection pending = pendingConnections.get(session.getId());
        if (pending == null || !pendingIsActive(session)) { closePending(session); return; }
        RealtimeHub.RealtimeConnection connection = hub.register(identity, frame -> {
            try {
                sendRaw(session, frame);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, () -> closeRaw(session));
        if (connection.isClosed() || ticker.getAsLong() - pending.deadline >= 0
                || !pending.completed.compareAndSet(false, true) || !session.isOpen()) {
            connection.close();
            closePending(session);
            return;
        }
        pendingConnections.remove(session.getId(), pending);
        session.getAttributes().put(ATTR_AUTH, identity);
        session.getAttributes().put(ATTR_USER, identity.user().id());
        session.getAttributes().put(ATTR_CONN, connection);
        connection.send("{\"type\":\"ready\",\"userId\":" + identity.user().id()
                + ",\"ts\":" + clock.millis() + "}");
    }

    public void sweepUnauthenticated() {
        long now = ticker.getAsLong();
        for (PendingConnection pending : pendingConnections.values()) {
            if (now - pending.deadline >= 0) { closePending(pending.session); }
        }
    }

    private boolean pendingIsActive(WebSocketSession session) {
        PendingConnection pending = pendingConnections.get(session.getId());
        return pending != null && !pending.completed.get() && session.isOpen()
                && ticker.getAsLong() - pending.deadline < 0;
    }

    private void closePending(WebSocketSession session) {
        PendingConnection pending = pendingConnections.remove(session.getId());
        if (pending == null || pending.completed.compareAndSet(false, true)) {
            security.closeTransport(() -> closeRaw(session));
        }
    }

    private static void closeRaw(WebSocketSession session) {
        try {
            if (session.isOpen()) { session.close(CloseStatus.POLICY_VIOLATION); }
        } catch (Exception ignored) {
            // Closing is best effort after the connection is already logically invalid.
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        PendingConnection pending = pendingConnections.remove(session.getId());
        if (pending != null) { pending.completed.set(true); }
        Object connection = session.getAttributes().get(ATTR_CONN);
        if (connection instanceof RealtimeHub.RealtimeConnection realtimeConnection) {
            realtimeConnection.close();
            hub.remove(realtimeConnection);
        }
    }

    private void sendError(WebSocketSession session, String code, String message) throws Exception {
        send(session, objectMapper.writeValueAsString(Map.of(
                "type", "error", "code", code, "message", message)));
    }

    /** WebSocketSession 不保证线程安全；同一连接可能被心跳和业务事件同时写。 */
    private void send(WebSocketSession session, String payload) throws Exception {
        Object attached = session.getAttributes().get(ATTR_CONN);
        if (attached instanceof RealtimeHub.RealtimeConnection connection) {
            connection.send(payload);
        } else {
            sendRaw(session, payload);
        }
    }

    private void sendRaw(WebSocketSession session, String payload) throws Exception {
        synchronized (session) {
            if (!session.isOpen()) { throw new IllegalStateException("实时连接已关闭"); }
            session.sendMessage(new TextMessage(payload));
        }
    }

    private static final class PendingConnection {
        private final WebSocketSession session;
        private final long deadline;
        private final AtomicBoolean completed = new AtomicBoolean();

        private PendingConnection(WebSocketSession session, long deadline) {
            this.session = session;
            this.deadline = deadline;
        }
    }
}
