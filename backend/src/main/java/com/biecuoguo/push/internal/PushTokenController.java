package com.biecuoguo.push.internal;

import com.biecuoguo.security.SecurityUtils;
import com.biecuoguo.web.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/push/tokens")
public class PushTokenController {
    public record RegisterRequest(String token, String platform, String environment) {}

    private final PushTokenService service;

    public PushTokenController(PushTokenService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<Boolean> register(@RequestBody RegisterRequest request) {
        var user = SecurityUtils.currentUser();
        service.register(user.id(), user.sessionId(), request.token(), request.platform(), request.environment());
        return ApiResponse.ok(true);
    }

    @DeleteMapping
    public ApiResponse<Boolean> unregister(@RequestParam String token) {
        service.unregister(token);
        return ApiResponse.ok(true);
    }
}
