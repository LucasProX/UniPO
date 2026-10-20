package com.biecuoguo.web;

import com.biecuoguo.service.RateLimitService;
import com.biecuoguo.state.RateLimitStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
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
    RateLimitService rateLimitService(RateLimitStore rateLimitStore) {
        return new RateLimitService(rateLimitStore);
    }

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
            RateLimitService rateLimitService,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Value("${app.rate-limit.enabled:true}") boolean enabled,
            @Value("${app.rate-limit.trust-forwarded-for:false}") boolean trustForwardedFor) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(
                new RateLimitFilter(rateLimitService, objectMapper, enabled, trustForwardedFor, meterRegistry));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
