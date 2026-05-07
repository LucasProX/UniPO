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

    @PostMapping("/verification-code")
    public ApiResponse<Boolean> requestVerificationCode(@Valid @RequestBody AuthDtos.VerificationRequest request) {
        authService.requestVerificationCode(request);
        return ApiResponse.ok(true);
    }

    @PostMapping("/verification-code/verify")
    public ApiResponse<Boolean> verifyCode(@Valid @RequestBody AuthDtos.VerifyCodeRequest request) {
        authService.verifyRegistrationCode(request);
        return ApiResponse.ok(true);
    }

    @GetMapping("/email-availability")
    public ApiResponse<AuthDtos.AvailabilityResponse> emailAvailability(@RequestParam String email, @RequestParam(defaultValue = "register") String purpose) {
        return ApiResponse.ok(authService.emailAvailability(email, purpose));
    }

    @GetMapping("/nickname-availability")
    public ApiResponse<AuthDtos.AvailabilityResponse> nicknameAvailability(@RequestParam String nickname) {
        return ApiResponse.ok(authService.nicknameAvailability(nickname));
    }

    @PostMapping("/reset-password")
    public ApiResponse<Boolean> resetPassword(@Valid @RequestBody AuthDtos.ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ApiResponse.ok(true);
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
