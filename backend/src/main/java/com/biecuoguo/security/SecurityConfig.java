package com.biecuoguo.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.biecuoguo.config.AppProperties;
import com.biecuoguo.web.ErrorCode;
import com.biecuoguo.web.ErrorResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter,
                                              SessionService sessionService, ObjectMapper objectMapper) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        // 全局异常处理器管不到 Security 过滤器链里抛出的认证异常
                        // （它在 DispatcherServlet 之外），所以这里手写一份，
                        // 响应格式必须和 GlobalExceptionHandler 保持一致。
                        .authenticationEntryPoint((request, response, ex) -> {
                            response.setStatus(HttpStatus.UNAUTHORIZED.value());
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding("UTF-8");
                            objectMapper.writeValue(response.getWriter(),
                                    ErrorResponse.of(ErrorCode.AUTH_UNAUTHENTICATED));
                        })
                        .accessDeniedHandler((request, response, ex) -> {
                            response.setStatus(HttpStatus.FORBIDDEN.value());
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding("UTF-8");
                            objectMapper.writeValue(response.getWriter(),
                                    ErrorResponse.of(ErrorCode.AUTH_FORBIDDEN));
                        })
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/auth/register", "/api/auth/login", "/api/auth/verification-code", "/api/auth/verification-code/verify", "/api/auth/reset-password", "/api/auth/email-availability", "/api/auth/nickname-availability", "/api/auth/refresh").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/categories", "/api/tags", "/api/schools").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/posts/**", "/api/options/**").permitAll()
                        // ApiVersionPrefixFilter 只改写「/api 上也存在」的路径，
                        // /api/v1/posts/page 这类 v1 独有端点会保留原样进来，
                        // 所以这里必须单独加一次白名单。
                        .requestMatchers(HttpMethod.GET, "/api/v1/posts/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/media/*/content").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/users/*", "/api/users/*/posts", "/api/users/*/likes", "/api/users/*/post-favorites").permitAll()
                        .requestMatchers("/api/admin/**").hasAnyRole("OPERATOR", "SCHOOL_OPERATOR", "COLLEGE_OPERATOR", "MAJOR_OPERATOR", "ADMIN")
                        // WebSocket 握手必须放行：浏览器握手带不了 Authorization 头，
                        // 真正的鉴权在握手拦截器（Cookie）或首条 auth 消息（Bearer）里做。
                        .requestMatchers("/api/realtime/ws", "/api/v1/realtime/ws").permitAll()
                        .anyRequest().authenticated()
                )
                // 两套认证入口：App 走 Bearer（JWT），浏览器走 httpOnly Cookie（服务端会话）。
                // 顺序很重要：JWT 先认，没有 Authorization 头时才回落到 Cookie。
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new SessionAuthenticationFilter(sessionService), UsernamePasswordAuthenticationFilter.class)
                // CSRF 只作用于 Cookie 认证的写请求；Bearer 请求不经过这里（不会自动带 Cookie）。
                .addFilterAfter(new CsrfFilter(sessionService, objectMapper), SessionAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AppProperties appProperties) {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = Arrays.stream(appProperties.cors().allowedOrigins().split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toList();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }
}
