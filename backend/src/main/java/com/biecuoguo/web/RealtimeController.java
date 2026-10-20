package com.biecuoguo.web;

import com.biecuoguo.security.SecurityUtils;
import com.biecuoguo.service.RealtimeHub;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 消息推送的长连接（D-6）。挂在 {@code /api/realtime/stream}，前端用 {@code /api/v1/realtime/stream}，
 * 由 {@code security/ApiVersionPrefixFilterConfig} 改写成前者。
 *
 * <p>没有进 {@code SecurityConfig} 的白名单：默认 {@code anyRequest().authenticated()} 就是我们要的，
 * 未登录连上来会照常拿到 401 JSON（而不是一条假连接）。
 */
@RestController
public class RealtimeController {
    private final RealtimeHub realtimeHub;

    public RealtimeController(RealtimeHub realtimeHub) {
        this.realtimeHub = realtimeHub;
    }

    @GetMapping(value = "/api/realtime/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return realtimeHub.subscribe(SecurityUtils.currentUser().id());
    }
}
