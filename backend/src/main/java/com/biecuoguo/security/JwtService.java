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

@Service
public class JwtService {
    private final AppProperties properties;

    public JwtService(AppProperties properties) {
        this.properties = properties;
    }

    public String createToken(CurrentUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(user.id()))
                .claim("email", user.email())
                .claim("role", user.role())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(properties.jwt().expirationMinutes() * 60)))
                .signWith(key())
                .compact();
    }

    public CurrentUser parse(String token) {
        Claims claims = parseClaims(token);
        return new CurrentUser(
                Long.valueOf(claims.getSubject()),
                claims.get("email", String.class),
                claims.get("role", String.class)
        );
    }

    public Instant expiresAt(String token) {
        return parseClaims(token).getExpiration().toInstant();
    }

    private Claims parseClaims(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key())
                .build()
                .parseSignedClaims(token)
                .getPayload();
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
