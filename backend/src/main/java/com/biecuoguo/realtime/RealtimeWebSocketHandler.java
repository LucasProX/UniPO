package com.biecuoguo.realtime;

import com.biecuoguo.security.CurrentUser;
import com.biecuoguo.security.JwtService;
import com.biecuoguo.security.TokenBlacklistService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;

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
 * <p>没鉴权成功的连接不注册任何订阅，也不会收到任何业务事件；30 秒内不认证直接断开
 * （由容器空闲超时兜底，见 {@link WebSocketConfig}）。
 */
@Component
public class RealtimeWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RealtimeWebSocketHandler.class);
    static final String ATTR_USER = "unipo.realtime.userId";
    static final String ATTR_CONN = "unipo.realtime.connection";

    private final RealtimeHub hub;
    private final JwtService jwtService;
    private final TokenBlacklistService tokenBlacklistService;
    private final ObjectMapper objectMapper;

    public RealtimeWebSocketHandler(RealtimeHub hub, JwtService jwtService,
                                    TokenBlacklistService tokenBlacklistService, ObjectMapper objectMapper) {
        this.hub = hub;
        this.jwtService = jwtService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Long userId = (Long) session.getAttributes().get(ATTR_USER);
        if (userId != null) {
            attach(session, userId);
            return;
        }
        send(session, "{\"type\":\"auth-required\"}");
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode node;
        try {
            node = objectMapper.readTree(message.getPayload());
        } catch (Exception e) {
            sendError(session, "VALIDATION_FAILED", "消息不是合法 JSON");
            return;
        }
        String type = node.path("type").asText("");
        switch (type) {
            case "auth" -> handleAuth(session, node.path("token").asText(""));
            case "resume" -> handleResume(session, node.path("lastEventId").asLong(0L));
            case "ping" -> send(session, "{\"type\":\"pong\",\"ts\":" + System.currentTimeMillis() + "}");
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
        Long userId = authenticate(token);
        if (userId == null) {
            sendError(session, "AUTH_UNAUTHENTICATED", "请先登录");
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        attach(session, userId);
    }

    private void handleResume(WebSocketSession session, long lastEventId) throws Exception {
        Long userId = (Long) session.getAttributes().get(ATTR_USER);
        if (userId == null) {
            sendError(session, "AUTH_UNAUTHENTICATED", "请先登录");
            return;
        }
        if (lastEventId > 0) {
            hub.replay(userId, lastEventId, frame -> {
                try {
                    send(session, frame);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        }
    }

    /** 绑定用户并注册连接。重复调用是幂等的（同一 session 只注册一次）。 */
    private void attach(WebSocketSession session, Long userId) throws Exception {
        if (session.getAttributes().get(ATTR_CONN) != null) {
            return;
        }
        RealtimeHub.RealtimeConnection connection = hub.register(userId, frame -> {
            try {
                send(session, frame);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        session.getAttributes().put(ATTR_USER, userId);
        session.getAttributes().put(ATTR_CONN, connection);
        send(session, "{\"type\":\"ready\",\"userId\":" + userId
                + ",\"ts\":" + System.currentTimeMillis() + "}");
    }

    private Long authenticate(String token) {
        if (token == null || token.isBlank() || tokenBlacklistService.isRevoked(token)) {
            return null;
        }
        try {
            CurrentUser user = jwtService.parse(token);
            return user.id();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
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
        synchronized (session) {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(payload));
            }
        }
    }
}
