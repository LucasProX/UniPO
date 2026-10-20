package com.biecuoguo.security;

import com.biecuoguo.state.SessionStore;
import com.biecuoguo.web.ErrorCode;
import com.biecuoguo.web.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Cookie 认证下的 CSRF 防护（双提交 Token）。
 *
 * <p>为什么需要：浏览器会自动带上 Cookie，攻击者只要让受害者打开一个页面就能伪造请求。
 * Authorization 头不会自动带，所以 Bearer 路径不需要这一步。
 *
 * <p>校验规则：只有「本次请求确实是通过会话 Cookie 认证的」才检查；
 * 安全方法（GET/HEAD/OPTIONS/TRACE）放行；其余方法必须让
 * {@code X-CSRF-Token} 等于服务端会话里的 csrfToken。
 *
 * <p>CSRF Token 放在非 HttpOnly 的 Cookie 里让前端读取，是业界常见的双提交方案：
 * 攻击者能发起请求，但读不到受害者的 Cookie（同源策略），也就填不出这个头。
 */
public class CsrfFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final SessionService sessionService;
    private final ObjectMapper objectMapper;

    public CsrfFilter(SessionService sessionService, ObjectMapper objectMapper) {
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Object attribute = request.getAttribute(SessionAuthenticationFilter.SESSION_ATTRIBUTE);
        if (attribute instanceof SessionStore.SessionData session && !SAFE_METHODS.contains(request.getMethod())) {
            String header = request.getHeader(sessionService.config().csrfHeaderName());
            if (header == null || !constantTimeEquals(header, session.csrfToken())) {
                response.setStatus(HttpStatus.FORBIDDEN.value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding("UTF-8");
                objectMapper.writeValue(response.getWriter(), ErrorResponse.of(ErrorCode.AUTH_CSRF_INVALID));
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    /** 定长比较，避免用字符串 equals 泄漏 token 前缀。 */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
