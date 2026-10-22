package com.biecuoguo.web;

import com.biecuoguo.dto.AuthDtos;
import com.biecuoguo.dto.UserProfile;
import com.biecuoguo.security.CookieSupport;
import com.biecuoguo.security.CurrentUser;
import com.biecuoguo.security.JwtService;
import com.biecuoguo.security.SecurityUtils;
import com.biecuoguo.security.SessionService;
import com.biecuoguo.security.SessionAuthenticationFilter;
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
    private final SessionService sessionService;

    public AuthController(AuthService authService, JwtService jwtService, SessionService sessionService) {
        this.authService = authService;
        this.jwtService = jwtService;
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

    @PostMapping("/sms-code")
    public ApiResponse<AuthDtos.SmsCodeResponse> requestSmsCode(@Valid @RequestBody AuthDtos.SmsCodeRequest request) {
        return ApiResponse.ok(authService.requestSmsCode(request.phone()));
    }

    @PostMapping("/sms-login")
    public ApiResponse<AuthDtos.AuthResponse> smsLogin(@Valid @RequestBody AuthDtos.SmsLoginRequest request,
                                                       HttpServletResponse response) {
        AuthDtos.AuthResponse auth = authService.smsLogin(request);
        issueWebSession(auth, response);
        return ApiResponse.ok(auth);
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
        String header = request.getHeader("Authorization");
        if (header == null && Boolean.TRUE.equals(request.getAttribute(SessionAuthenticationFilter.SESSION_UNAVAILABLE_ATTRIBUTE))) {
            throw new IllegalStateException("会话暂时无法确认，请稍后重试退出");
        }
        CurrentUser credential = null;
        if (header != null && header.startsWith("Bearer ")) {
            try { credential = jwtService.parse(header.substring(7)); }
            catch (RuntimeException ignored) { /* An expired access may use a valid refresh below. */ }
        } else if (header == null) {
            try { credential = SecurityUtils.currentUser(); }
            catch (org.springframework.security.core.AuthenticationException ignored) { }
        }
        if (body != null && body.refreshToken() != null && !body.refreshToken().isBlank()) {
            CurrentUser refresh;
            try {
                refresh = jwtService.parseRefresh(body.refreshToken());
            } catch (RuntimeException ex) {
                if (credential == null) {
                    throw new org.springframework.security.authentication.InsufficientAuthenticationException("登录信息已失效");
                }
                refresh = credential;
            }
            if (credential != null && (!credential.id().equals(refresh.id())
                    || !java.util.Objects.equals(credential.sessionId(), refresh.sessionId()))) {
                throw new IllegalArgumentException("登录信息不属于同一设备");
            }
            credential = refresh;
        }
        if (credential == null) {
            if (header != null || body != null && body.refreshToken() != null) {
                throw new org.springframework.security.authentication.InsufficientAuthenticationException("请重新登录");
            }
            clearWebSession(response);
            return ApiResponse.ok(true);
        }
        // Durable device revocation covers every token derived by refresh. Storage failures propagate.
        authService.revokeDevice(credential);
        authService.logout(credential.id());
        String sessionId = sessionIdFrom(request);
        if (header == null && sessionId != null) {
            sessionService.revoke(sessionId, credential.id());
        }
        if (header == null) { clearWebSession(response); }
        return ApiResponse.ok(true);
    }

    @PostMapping("/logout-all")
    public ApiResponse<Boolean> logoutAll(HttpServletResponse response) {
        sessionService.revokeAllForUser(SecurityUtils.currentUser().id());
        clearWebSession(response);
        return ApiResponse.ok(true);
    }

    @PostMapping("/change-password")
    public ApiResponse<Boolean> changePassword(@Valid @RequestBody AuthDtos.ChangePasswordRequest body,
                                               HttpServletResponse response) {
        authService.changePassword(SecurityUtils.currentUser(), body);
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
        SessionService.IssuedSession session = sessionService.create(jwtService.parse(auth.token()));
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
