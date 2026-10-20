package com.biecuoguo.config;

import com.biecuoguo.realtime.RealtimeHandshakeInterceptor;
import com.biecuoguo.realtime.RealtimeWebSocketHandler;
import com.biecuoguo.security.SessionService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.Arrays;

/**
 * WebSocket 注册。路径挂在 /api/realtime/ws，前端走 /api/v1/realtime/ws（由版本前缀过滤器改写）。
 *
 * <p>握手放行不代表能收事件：鉴权在握手拦截器（Cookie）或首条 auth 消息（Bearer）里完成。
 * 允许的 Origin 跟 CORS 用同一份配置，避免出现「HTTP 能调、WS 被拒」的分裂。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final RealtimeWebSocketHandler handler;
    private final SessionService sessionService;
    private final AppProperties appProperties;

    public WebSocketConfig(RealtimeWebSocketHandler handler, SessionService sessionService,
                           AppProperties appProperties) {
        this.handler = handler;
        this.sessionService = sessionService;
        this.appProperties = appProperties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        String[] origins = Arrays.stream(appProperties.cors().allowedOrigins().split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toArray(String[]::new);
        registry.addHandler(handler, "/api/realtime/ws")
                .addInterceptors(new RealtimeHandshakeInterceptor(sessionService))
                .setAllowedOriginPatterns(origins.length == 0 ? new String[]{"*"} : origins);
    }
}
