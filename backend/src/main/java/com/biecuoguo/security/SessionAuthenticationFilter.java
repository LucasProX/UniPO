package com.biecuoguo.security;

import com.biecuoguo.state.SessionStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 从 httpOnly Cookie 恢复登录态（Web 端）。
 *
 * <p>顺序在 {@link JwtAuthenticationFilter} 之后：带 Bearer 的请求（App / 脚本）先被 JWT 认掉，
 * 这里只处理「没有 Authorization 头、但带了会话 Cookie」的浏览器请求。
 * 两套认证入口共用同一个 {@link CurrentUser} 与权限模型。
 *
 * <p>会话数据会被挂到 request attribute 上，供 {@link CsrfFilter} 校验双提交 token。
 */
public class SessionAuthenticationFilter extends OncePerRequestFilter {

    /** request attribute：当前请求使用的服务端会话；null 表示不是 Cookie 认证。 */
    public static final String SESSION_ATTRIBUTE = "unipo.session";
    public static final String SESSION_UNAVAILABLE_ATTRIBUTE = "unipo.session.unavailable";

    private final SessionService sessionService;

    public SessionAuthenticationFilter(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (request.getHeader("Authorization") == null && SecurityContextHolder.getContext().getAuthentication() == null) {
            String sessionId = readSessionCookie(request);
            if (sessionId != null) {
                try { sessionService.resolve(sessionId).ifPresent(data -> {
                    CurrentUser user = new CurrentUser(data.userId(), data.email(), data.role(), data.authVersion(), data.deviceSessionId());
                    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                            user, null, List.of(new SimpleGrantedAuthority("ROLE_" + data.role()))));
                    request.setAttribute(SESSION_ATTRIBUTE, data);
                }); } catch (RuntimeException ex) {
                    request.setAttribute(SESSION_UNAVAILABLE_ATTRIBUTE, Boolean.TRUE);
                    SecurityContextHolder.clearContext();
                }
            }
        }
        filterChain.doFilter(request, response);
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
