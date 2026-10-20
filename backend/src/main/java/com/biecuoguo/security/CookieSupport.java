package com.biecuoguo.security;

import com.biecuoguo.config.AppProperties;
import org.springframework.http.ResponseCookie;

import java.time.Duration;

/**
 * 认证 Cookie 的构造与清除。
 *
 * <p>两个 Cookie 分工明确：
 * <ul>
 *   <li><b>session</b>：HttpOnly，JS 读不到，XSS 偷不走；</li>
 *   <li><b>csrf</b>：故意非 HttpOnly，前端读出来放进 X-CSRF-Token 头 —— 这就是双提交校验。</li>
 * </ul>
 *
 * <p>SameSite 用 Lax：跨站 POST 不带 Cookie，挡掉大部分 CSRF；
 * 再叠加双提交校验，挡住同站 XSS 之外的场景。Secure 在生产必须开，本地 http 由 profile 关掉。
 */
public final class CookieSupport {

    private CookieSupport() {
    }

    public static ResponseCookie sessionCookie(AppProperties.Auth auth, String value, Duration ttl) {
        return base(auth.sessionCookieName(), auth, value, ttl).httpOnly(true).build();
    }

    public static ResponseCookie csrfCookie(AppProperties.Auth auth, String value, Duration ttl) {
        return base(auth.csrfCookieName(), auth, value, ttl).httpOnly(false).build();
    }

    public static ResponseCookie clearSessionCookie(AppProperties.Auth auth) {
        return base(auth.sessionCookieName(), auth, "", Duration.ZERO).httpOnly(true).build();
    }

    public static ResponseCookie clearCsrfCookie(AppProperties.Auth auth) {
        return base(auth.csrfCookieName(), auth, "", Duration.ZERO).httpOnly(false).build();
    }

    private static ResponseCookie.ResponseCookieBuilder base(String name, AppProperties.Auth auth,
                                                             String value, Duration ttl) {
        return ResponseCookie.from(name, value)
                .path("/")
                .maxAge(ttl)
                .secure(auth.cookieSecure())
                .sameSite(auth.cookieSameSite());
    }
}
