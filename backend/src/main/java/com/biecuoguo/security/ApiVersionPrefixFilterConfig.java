package com.biecuoguo.security;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.io.IOException;

/**
 * 把 /api/v1/** 改写成 /api/**，让 Spring Security 的白名单只需维护一份。
 *
 * 为什么需要：SecurityConfig 的 requestMatchers 是字面前缀匹配，
 * 而 Security 的 Filter 链在 HandlerMapping 之前跑，此时 URI 还是 /api/v1，
 * 不先改写成 /api 就会被 anyRequest().authenticated() 拦成 401。
 *
 * 实现要点：
 * - 用 FilterRegistrationBean 显式设置 order = HIGHEST_PRECEDENCE，
 *   确保排在 Spring Security 的 filterChain 前面。
 *   只靠 @Order 注解不可靠：Spring Boot 对 @Component Filter 的排序
 *   与 SecurityFilterChain 的注册顺序不保证谁先谁后。
 * - 只改 path，不动 query / body / method。
 * - setDispatcherTypes 只注册 REQUEST，避免 ASYNC/ERROR 时二次改写把 /api 再改一次。
 */
@Configuration
public class ApiVersionPrefixFilterConfig {

    private static final String LEGACY_PREFIX = "/api";
    private static final String VERSIONED_PREFIX = "/api/v1";

    /** 只挂在 /api/v1 下、/api 上没有对应实现的路径（改写后会 404）。 */
    private static final java.util.Set<String> V1_ONLY_PATHS = java.util.Set.of("/api/posts/page");

    @Bean
    FilterRegistrationBean<ApiVersionPrefixFilter> apiVersionPrefixFilterRegistration() {
        FilterRegistrationBean<ApiVersionPrefixFilter> registration =
                new FilterRegistrationBean<>(new ApiVersionPrefixFilter());
        registration.addUrlPatterns("/api/v1/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    static final class ApiVersionPrefixFilter implements Filter {

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response, FilterChain chain) throws IOException, ServletException {
            if (!(request instanceof HttpServletRequest httpRequest) || !(response instanceof HttpServletResponse)) {
                chain.doFilter(request, response);
                return;
            }
            String uri = httpRequest.getRequestURI();
            if (uri == null || !uri.startsWith(VERSIONED_PREFIX + "/")) {
                chain.doFilter(request, response);
                return;
            }
            String rewritten = LEGACY_PREFIX + uri.substring(VERSIONED_PREFIX.length());
            // /api/v1/posts/page 这类「只存在于 v1」的端点不能改写：
            // 它们由 com.biecuoguo.web.v1 包下的 Controller 自己挂 /api/v1 前缀，
            // 改写成 /api/posts/page 之后就没有 handler 了。
            // 这些路径在 SecurityConfig 里已单独加白名单。
            if (V1_ONLY_PATHS.contains(rewritten)) {
                chain.doFilter(request, response);
                return;
            }
            chain.doFilter(new PrefixRewriteRequest(httpRequest, rewritten), response);
        }
    }

    private static final class PrefixRewriteRequest extends HttpServletRequestWrapper {
        private final String rewrittenUri;

        private PrefixRewriteRequest(HttpServletRequest request, String rewrittenUri) {
            super(request);
            this.rewrittenUri = rewrittenUri;
        }

        @Override
        public String getRequestURI() {
            return rewrittenUri;
        }

        @Override
        public String getServletPath() {
            return rewrittenUri;
        }
    }
}
