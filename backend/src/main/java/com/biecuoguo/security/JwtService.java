package com.biecuoguo.security;

import com.biecuoguo.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 签发与校验。
 *
 * <p>两种 token，靠 {@code typ} 声明区分：
 * <ul>
 *   <li>{@code access}：调接口用，有效期短（{@code app.jwt.expiration-minutes}，默认 120 分钟）；</li>
 *   <li>{@code refresh}：只能拿去换新的 access token，有效期长（{@code app.jwt.refresh-expiration-minutes}，默认 14 天）。</li>
 * </ul>
 *
 * <p><b>两种 token 不能混用</b>：{@link #parse} 只认 access，{@link #parseRefresh} 只认 refresh。
 * 混用时抛出的异常会被 {@code JwtAuthenticationFilter} 吞掉当成未登录，
 * 所以拿 refresh token 去调接口拿到的是 401，而不是一个能用 14 天的登录态。
 */
@Service
public class JwtService {
    private static final String CLAIM_TYPE = "typ";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final AppProperties properties;

    public JwtService(AppProperties properties) {
        this.properties = properties;
    }

    public String createToken(CurrentUser user) {
        return build(user, TYPE_ACCESS, properties.jwt().expirationMinutes() * 60);
    }

    public String createRefreshToken(CurrentUser user) {
        return build(user, TYPE_REFRESH, properties.jwt().refreshExpirationMinutes() * 60);
    }

    /** access token 的有效期（秒）。客户端拿它算「还有多久该刷新了」。 */
    public long accessExpiresInSeconds() {
        return properties.jwt().expirationMinutes() * 60;
    }

    /** 解析 access token。refresh token 传进来会抛异常。 */
    public CurrentUser parse(String token) {
        return toUser(parseClaims(token, TYPE_ACCESS));
    }

    /** 解析 refresh token。access token 传进来会抛异常。 */
    public CurrentUser parseRefresh(String token) {
        return toUser(parseClaims(token, TYPE_REFRESH));
    }

    /** 只管过期时间，不校验类型（退出登录时要把两种 token 都记进拉黑表）。 */
    public Instant expiresAt(String token) {
        return parseClaims(token, null).getExpiration().toInstant();
    }

    private String build(CurrentUser user, String type, long ttlSeconds) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(user.id()))
                .claim("email", user.email())
                .claim("role", user.role())
                .claim(CLAIM_TYPE, type)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key())
                .compact();
    }

    private CurrentUser toUser(Claims claims) {
        return new CurrentUser(
                Long.valueOf(claims.getSubject()),
                claims.get("email", String.class),
                claims.get("role", String.class)
        );
    }

    private Claims parseClaims(String token, String expectedType) {
        Claims claims = Jwts.parser()
                .verifyWith(key())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        if (expectedType != null) {
            String actual = claims.get(CLAIM_TYPE, String.class);
            // 这次改动之前签发的 token 没有 typ。一律当成 access，
            // 否则上线那一刻所有在线用户会被踢下线。
            if (actual == null) {
                actual = TYPE_ACCESS;
            }
            if (!expectedType.equals(actual)) {
                throw new IllegalArgumentException("token 类型不匹配");
            }
        }
        return claims;
    }

    private SecretKey key() {
        String secret = properties.jwt().secret();
        // 以前这里会把短密钥补 '0' 凑到 32 字节——那会让一个 3 位密钥看起来「能用」，
        // 实际强度等于 3 位。签名密钥不合格应该在启动时就炸（见 StartupSafetyGuard），
        // 而不是在这里悄悄修补。
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT 签名密钥缺失或短于 32 字节，无法签发/校验 token");
        }
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
