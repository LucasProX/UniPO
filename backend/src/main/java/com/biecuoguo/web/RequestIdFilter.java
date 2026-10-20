package com.biecuoguo.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 给每个请求分配 requestId，并回写响应头 {@code X-Request-Id}。
 *
 * <p>优先级最高：要在 Spring Security 和任何日志之前跑，
 * 这样后续日志（含 MDC）都能带上同一个 id。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    private static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = request.getHeader(RequestContextHolder.HEADER);
        if (id == null || id.isBlank() || id.length() > 64) {
            id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        RequestContextHolder.set(id);
        org.slf4j.MDC.put(MDC_KEY, id);
        response.setHeader(RequestContextHolder.HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            RequestContextHolder.clear();
            org.slf4j.MDC.remove(MDC_KEY);
        }
    }
}