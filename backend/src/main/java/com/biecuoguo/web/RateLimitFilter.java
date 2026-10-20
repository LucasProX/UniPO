package com.biecuoguo.web;

import com.biecuoguo.service.RateLimitService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * 按「来源 IP + 接口」限流，命中上限回 429 + {@code Retry-After}。
 *
 * <p>放在版本前缀改写之后（order = HIGHEST_PRECEDENCE + 2），这样 {@code /api/v1/auth/login}
 * 已经被改写成 {@code /api/auth/login}，规则表只需要写一份路径。
 *
 * <p>响应体沿用统一错误包（{@link ErrorResponse}），否则 App 端要为一个 429 单独写一套解析。
 *
 * <p><b>关于取哪个 IP</b>：默认用 {@code request.getRemoteAddr()}。部署时前面有 Caddy，
 * 所有请求的 remoteAddr 都是 Caddy——那样所有人共用一个桶，等于全站被一个人拖下水。
 * 所以需要打开 {@code app.rate-limit.trust-forwarded-for}（compose 里默认开），改成读
 * {@code X-Forwarded-For} 的第一段。这个头可以伪造，所以只有在「前面确实有代理、
 * 且外部连不到后端」的部署下才能开。
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** 一条限流规则：方法 + 路径 + 窗口内允许次数。 */
    private record Rule(String name, String method, String path, int limit, Duration window) {
    }

    /**
     * 规则表。数值刻意留了余量——本地 smoke 一次要跑几十个认证请求，
     * 上限压太死会把自己挡在门外；这里的目标是「脚本刷不动」，不是「精确配额」。
     */
    private static final List<Rule> RULES = List.of(
            // 登录：撞库的主战场
            new Rule("login", "POST", "/api/auth/login", 30, Duration.ofMinutes(5)),
            // 验证码：这条最要紧，每发一封邮件都是真实成本，而且能拿去轰炸别人的邮箱
            new Rule("verification-code", "POST", "/api/auth/verification-code", 20, Duration.ofMinutes(10)),
            new Rule("register", "POST", "/api/auth/register", 20, Duration.ofHours(1)),
            new Rule("reset-password", "POST", "/api/auth/reset-password", 20, Duration.ofMinutes(10)),
            // 上传：每张图都占存储和带宽
            new Rule("upload", "POST", "/api/media/upload", 30, Duration.ofMinutes(5)),
            new Rule("post-create", "POST", "/api/posts", 20, Duration.ofMinutes(5))
    );

    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final boolean trustForwardedFor;
    private final MeterRegistry meterRegistry;

    public RateLimitFilter(RateLimitService rateLimitService, ObjectMapper objectMapper,
                          boolean enabled, boolean trustForwardedFor, MeterRegistry meterRegistry) {
        this.rateLimitService = rateLimitService;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.trustForwardedFor = trustForwardedFor;
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }
        Rule rule = match(request);
        if (rule == null) {
            chain.doFilter(request, response);
            return;
        }
        String ip = clientIp(request);
        RateLimitService.Result result = rateLimitService.acquire(rule.name() + "|" + ip, rule.limit(), rule.window());
        if (result.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("Rate limit hit. rule={}, ip={}, path={}, retryAfter={}s",
                rule.name(), ip, request.getRequestURI(), result.retryAfterSeconds());
        // 命中率是「限流规则是否合理」的唯一客观指标：某个 rule 一直涨，要么是攻击，要么阈值太低。
        Counter.builder("unipo.ratelimit.blocked")
                .tag("rule", rule.name())
                .register(meterRegistry)
                .increment();
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(result.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(),
                ErrorResponse.of(ErrorCode.RATE_LIMITED,
                        "操作太频繁了，请 " + result.retryAfterSeconds() + " 秒后再试"));
    }

    private Rule match(HttpServletRequest request) {
        String path = request.getRequestURI();
        for (Rule rule : RULES) {
            if (rule.path().equals(path) && rule.method().equalsIgnoreCase(request.getMethod())) {
                return rule;
            }
        }
        return null;
    }

    private String clientIp(HttpServletRequest request) {
        if (trustForwardedFor) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String first = forwarded.split(",", 2)[0].trim();
                if (!first.isEmpty() && first.length() <= 45) {
                    return first;
                }
            }
        }
        return request.getRemoteAddr();
    }
}
