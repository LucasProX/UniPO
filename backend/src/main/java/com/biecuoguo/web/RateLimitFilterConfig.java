package com.biecuoguo.web;

import com.biecuoguo.service.RateLimitService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 注册 {@link RateLimitFilter}。
 *
 * <p>顺序要求：RequestIdFilter(HIGHEST_PRECEDENCE)
 * -> ApiVersionPrefixFilter(+1) -> RateLimitFilter(+2) -> Spring Security。
 *
 * <p>限流必须在版本前缀改写<b>之后</b>：改写后路径统一成 {@code /api/...}，
 * 规则表才不用为 {@code /api/v1/...} 再写一遍。
 */
@Configuration
public class RateLimitFilterConfig {

    @Bean
    RateLimitService rateLimitService() {
        return new RateLimitService();
    }

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
            RateLimitService rateLimitService,
            ObjectMapper objectMapper,
            @Value("${app.rate-limit.enabled:true}") boolean enabled,
            @Value("${app.rate-limit.trust-forwarded-for:false}") boolean trustForwardedFor) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(
                new RateLimitFilter(rateLimitService, objectMapper, enabled, trustForwardedFor));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
