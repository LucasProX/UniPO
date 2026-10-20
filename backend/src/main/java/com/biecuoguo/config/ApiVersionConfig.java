package com.biecuoguo.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 接口版本化说明。
 *
 * 版本前缀的处理全部在 com.biecuoguo.security.ApiVersionPrefixFilter 里完成：
 * 它把 /api/v1/** 改写成 /api/** 后再往下传，因此这里**不需要**
 * 用 WebMvcConfigurer.configurePathMatch 的 addPathPrefix。
 *
 * 为什么不用 addPathPrefix（2026-10-20 实测踩坑）：
 * addPathPrefix 会重建整套路径匹配配置，导致 Spring Security 的
 * AntPathRequestMatcher 白名单整体失效——/api/schools、/api/auth/login 这些
 * 本来就 permitAll 的 GET 全部变成 401，而且是全局性的，不只是新前缀受影响。
 * 过滤器方案只改 URI，不动匹配器，风险面小得多。
 *
 * 保留这个空 Configuration 是为了把上面这条结论钉在代码里，
 * 避免以后有人再试一次 addPathPrefix。
 */
@Configuration
public class ApiVersionConfig implements WebMvcConfigurer {
}
