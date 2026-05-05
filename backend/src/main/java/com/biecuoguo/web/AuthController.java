package com.biecuoguo.web;

import com.biecuoguo.dto.AuthDtos;
import com.biecuoguo.dto.UserProfile;
import com.biecuoguo.security.JwtService;
import com.biecuoguo.security.SecurityUtils;
import com.biecuoguo.security.TokenBlacklistService;
import com.biecuoguo.service.AuthService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;
    private final JwtService jwtService;
    private final TokenBlacklistService tokenBlacklistService;

    public AuthController(AuthService authService, JwtService jwtService, TokenBlacklistService tokenBlacklistService) {
        this.authService = authService;
        this.jwtService = jwtService;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    @PostMapping("/register")
    public ApiResponse<AuthDtos.AuthResponse> register(@Valid @RequestBody AuthDtos.RegisterRequest request) {
        return ApiResponse.ok(authService.register(request));
    }

    @PostMapping("/login")
    public ApiResponse<AuthDtos.AuthResponse> login(@Valid @RequestBody AuthDtos.LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @PostMapping("/logout")
    public ApiResponse<Boolean> logout(HttpServletRequest request) {
        Long currentUserId = null;
        try {
            currentUserId = SecurityUtils.currentUser().id();
        } catch (RuntimeException ignored) {
            // The token may already be invalid or missing; still revoke it below when possible.
        }
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            tokenBlacklistService.revoke(token, jwtService.expiresAt(token));
        }
        if (currentUserId != null) {
            authService.logout(currentUserId);
        }
        return ApiResponse.ok(true);
    }

    @PostMapping("/heartbeat")
    public ApiResponse<Boolean> heartbeat() {
        return ApiResponse.ok(authService.heartbeat(SecurityUtils.currentUser().id()));
    }

    @GetMapping("/me")
    public ApiResponse<UserProfile> me() {
        return ApiResponse.ok(authService.me(SecurityUtils.currentUser().id()));
    }
}
