package com.biecuoguo.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 注册 {@link RequestIdFilter}，order 比版本前缀过滤器还靠前。
 *
 * <p>顺序要求：RequestIdFilter(HIGHEST_PRECEDENCE)
 *  -> ApiVersionPrefixFilter(HIGHEST_PRECEDENCE + 1)
 *  -> Spring Security 链
 *  -> Controller。
 * 这样改写 URI 时 requestId 已经就位，改写出错也能带上 id 排查。
 */
@Configuration
public class RequestIdFilterConfig {

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilterRegistration() {
        FilterRegistrationBean<RequestIdFilter> registration =
                new FilterRegistrationBean<>(new RequestIdFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}