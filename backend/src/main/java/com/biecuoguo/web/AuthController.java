package com.biecuoguo.web;

import com.biecuoguo.dto.AuthDtos;
import com.biecuoguo.dto.UserProfile;
import com.biecuoguo.security.CookieSupport;
import com.biecuoguo.security.JwtService;
import com.biecuoguo.security.SecurityUtils;
import com.biecuoguo.security.SessionService;
import com.biecuoguo.security.TokenBlacklistService;
import com.biecuoguo.service.AuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;
    private final JwtService jwtService;
    private final TokenBlacklistService tokenBlacklistService;
    private final SessionService sessionService;

    public AuthController(AuthService authService, JwtService jwtService,
                          TokenBlacklistService tokenBlacklistService, SessionService sessionService) {
        this.authService = authService;
        this.jwtService = jwtService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.sessionService = sessionService;
    }

    @PostMapping("/register")
    public ApiResponse<AuthDtos.AuthResponse> register(@Valid @RequestBody AuthDtos.RegisterRequest request,
                                                       HttpServletResponse response) {
        AuthDtos.AuthResponse auth = authService.register(request);
        issueWebSession(auth, response);
        return ApiResponse.ok(auth);
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
    public ApiResponse<AuthDtos.AuthResponse> login(@Valid @RequestBody AuthDtos.LoginRequest request,
                                                    HttpServletResponse response) {
        AuthDtos.AuthResponse auth = authService.login(request);
        issueWebSession(auth, response);
        return ApiResponse.ok(auth);
    }

    @PostMapping("/refresh")
    public ApiResponse<AuthDtos.AuthResponse> refresh(@Valid @RequestBody AuthDtos.RefreshRequest request,
                                                      HttpServletResponse response) {
        AuthDtos.AuthResponse auth = authService.refresh(request.refreshToken());
        // 浏览器刷新 access token 时顺手换一个新会话：旧 session id 不复用，降低被固定的风险。
        issueWebSession(auth, response);
        return ApiResponse.ok(auth);
    }

    @PostMapping("/logout")
    public ApiResponse<Boolean> logout(HttpServletRequest request,
                                          @RequestBody(required = false) AuthDtos.LogoutRequest body,
                                          HttpServletResponse response) {
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
        // refresh token 也拉黑，否则退出登录后它还能换出一对新的 access token。
        if (body != null && body.refreshToken() != null && !body.refreshToken().isBlank()) {
            try {
                tokenBlacklistService.revoke(body.refreshToken(), jwtService.expiresAt(body.refreshToken()));
            } catch (RuntimeException ignored) {
                // 已经过期或格式不对的 refresh token 不用拉黑，本来也用不了。
            }
        }
        if (currentUserId != null) {
            authService.logout(currentUserId);
        }
        // Cookie 会话也要跟着失效，否则浏览器这边「退出」了还能继续用旧 Cookie 调接口。
        String sessionId = sessionIdFrom(request);
        if (sessionId != null && currentUserId != null) {
            sessionService.revoke(sessionId, currentUserId);
        }
        clearWebSession(response);
        return ApiResponse.ok(true);
    }

    /**
     * 浏览器登录成功后同时下发两个 Cookie：
     * HttpOnly 的 session id（JS 读不到），以及非 HttpOnly 的 csrf token（前端读出来放请求头）。
     *
     * <p>App 仍然用响应体里的 Bearer token；Cookie 对它无意义，也不会被保存。
     */
    private void issueWebSession(AuthDtos.AuthResponse auth, HttpServletResponse response) {
        var user = auth.user();
        SessionService.IssuedSession session = sessionService.create(user.id(), user.role(), user.email());
        var config = sessionService.config();
        response.addHeader(HttpHeaders.SET_COOKIE,
                CookieSupport.sessionCookie(config, session.sessionId(), sessionService.ttl()).toString());
        response.addHeader(HttpHeaders.SET_COOKIE,
                CookieSupport.csrfCookie(config, session.csrfToken(), sessionService.ttl()).toString());
    }

    private void clearWebSession(HttpServletResponse response) {
        var config = sessionService.config();
        response.addHeader(HttpHeaders.SET_COOKIE, CookieSupport.clearSessionCookie(config).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, CookieSupport.clearCsrfCookie(config).toString());
    }

    private String sessionIdFrom(HttpServletRequest request) {
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

    @PostMapping("/heartbeat")
    public ApiResponse<Boolean> heartbeat() {
        return ApiResponse.ok(authService.heartbeat(SecurityUtils.currentUser().id()));
    }

    @GetMapping("/me")
    public ApiResponse<UserProfile> me() {
        return ApiResponse.ok(authService.me(SecurityUtils.currentUser().id()));
    }
}
