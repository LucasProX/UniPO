package com.biecuoguo.realtime;

import com.biecuoguo.security.SessionService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * 握手阶段从 httpOnly Cookie 恢复用户。
 *
 * <p>浏览器发起 WebSocket 时不能自定义 Authorization 头，但会自动带上同站 Cookie——
 * 这正是 D3 选 Cookie 认证的额外收益：浏览器实时通道不需要把 token 塞进 query
 * （query 会进访问日志、浏览器历史和 Referer）。
 *
 * <p>没有 Cookie 也放行：App 走「连接后 auth 消息」的路径，由 handler 负责。
 */
public class RealtimeHandshakeInterceptor implements HandshakeInterceptor {

    private final SessionService sessionService;

    public RealtimeHandshakeInterceptor(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            HttpServletRequest http = servletRequest.getServletRequest();
            String sessionId = readSessionCookie(http);
            if (sessionId != null) {
                sessionService.resolve(sessionId).ifPresent(data ->
                        attributes.put(RealtimeWebSocketHandler.ATTR_USER, data.userId()));
            }
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // nothing to do
    }

    private String readSessionCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        String name = sessionService.config().sessionCookieName();
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
