package com.biecuoguo.security;

import com.biecuoguo.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JWT 的两种类型与有效期。
 *
 * <p>这里断言的是「拿 refresh token 调接口必须失败」这件事——它是整条刷新链路的安全边界，
 * 也是这次改造最容易被人「顺手简化」掉的地方（把 parse/parseRefresh 合成一个方法就没了）。
 */
class JwtServiceTest {
    private static final String SECRET = "unit-test-secret-0123456789abcdef0123456789";
    private static final CurrentUser USER = new CurrentUser(7L, "u@example.com", "USER", 0L, "unit-device-session");

    private static JwtService service(String secret, long accessMinutes, long refreshMinutes) {
        return new JwtService(new AppProperties(
                null, new AppProperties.Jwt(secret, accessMinutes, refreshMinutes), null, null, null, null, null));
    }

    private static JwtService service() {
        return service(SECRET, 120, 20160);
    }

    @Test
    void accessTokenRoundTrips() {
        CurrentUser parsed = service().parse(service().createToken(USER));
        assertEquals(7L, parsed.id());
        assertEquals("u@example.com", parsed.email());
        assertEquals("USER", parsed.role());
    }

    @Test
    void refreshTokenCannotBeUsedAsAccessToken() {
        JwtService jwt = service();
        assertThrows(IllegalArgumentException.class, () -> jwt.parse(jwt.createRefreshToken(USER)));
    }

    @Test
    void accessTokenCannotBeUsedAsRefreshToken() {
        JwtService jwt = service();
        assertThrows(IllegalArgumentException.class, () -> jwt.parseRefresh(jwt.createToken(USER)));
    }

    @Test
    void expiresAtWorksForBothTypes() {
        JwtService jwt = service();
        Instant now = Instant.now();
        assertTrue(jwt.expiresAt(jwt.createToken(USER)).isAfter(now.plusSeconds(7100)));
        // 退出登录要把 refresh 也拉黑，所以 expiresAt 不能只认 access
        assertTrue(jwt.expiresAt(jwt.createRefreshToken(USER)).isAfter(jwt.expiresAt(jwt.createToken(USER))));
    }

    @Test
    void accessExpiresInSecondsFollowsConfig() {
        assertEquals(7200, service().accessExpiresInSeconds());
        assertEquals(60, service(SECRET, 1, 20160).accessExpiresInSeconds());
    }

    @Test
    void shortSecretIsRejectedInsteadOfPadded() {
        JwtService weak = service("short", 120, 20160);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> weak.createToken(USER));
        assertTrue(ex.getMessage().contains("32"));
    }

    @Test
    void expiredTokenIsRejected() {
        JwtService expired = service(SECRET, -1, 20160);
        String token = expired.createToken(USER);
        assertThrows(RuntimeException.class, () -> expired.parse(token));
    }

    @Test
    void deviceAndVersionSurviveRoundTripAndBoundTokenLifetime() {
        JwtService jwt = service();
        Instant deviceDeadline = Instant.now().plusSeconds(30);
        String access = jwt.createToken(USER, deviceDeadline);
        String refresh = jwt.createRefreshToken(USER, deviceDeadline);
        assertEquals(USER, jwt.parse(access));
        assertEquals(USER, jwt.parseRefresh(refresh));
        assertTrue(!jwt.expiresAt(access).isAfter(deviceDeadline));
        assertTrue(!jwt.expiresAt(refresh).isAfter(deviceDeadline));
    }

    @Test
    void oldUnversionedAndTypelessCredentialsRequireNewAuthentication() {
        var key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (String type : new String[]{"access", "refresh", ""}) {
            var builder = io.jsonwebtoken.Jwts.builder().subject("7").claim("role", "ADMIN")
                    .expiration(java.util.Date.from(Instant.now().plusSeconds(300)));
            if (!type.isBlank()) { builder.claim("typ", type); }
            String old = builder.signWith(key).compact();
            assertThrows(RuntimeException.class, () -> service().parse(old));
            assertThrows(RuntimeException.class, () -> service().parseRefresh(old));
        }
        assertThrows(IllegalArgumentException.class, () -> service().createToken(new CurrentUser(7L, "fixture", "USER")));
    }

    @Test
    void issuingTwiceInOneSecondStillProducesDistinctCredentials() {
        org.junit.jupiter.api.Assertions.assertNotEquals(service().createToken(USER), service().createToken(USER));
    }
}
