package com.biecuoguo.security;

import com.biecuoguo.config.AppProperties;
import com.biecuoguo.state.SessionStore;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * Web 会话的创建 / 校验 / 吊销。
 *
 * <p>Cookie 里只放不透明 session id；userId / role / csrfToken 全在服务端（Redis 或内存）。
 * 这样「退出所有设备」「改密即失效」才成立，而这些用纯 JWT 做不到。
 *
 * <p>session id 和 csrf token 都是 32 字节随机数（Base64URL 无填充），
 * 不许用 UUID —— UUIDv4 只有 122 bit 随机性，而且格式可猜。
 */
@Service
public class SessionService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final SessionStore store;
    private final AppProperties.Auth auth;

    public SessionService(SessionStore store, AppProperties properties) {
        this.store = store;
        this.auth = properties.auth();
    }

    public IssuedSession create(long userId, String role, String email) {
        String sessionId = randomToken();
        String csrfToken = randomToken();
        store.save(sessionId, new SessionStore.SessionData(userId, role, email, csrfToken), ttl());
        return new IssuedSession(sessionId, csrfToken);
    }

    public Optional<SessionStore.SessionData> resolve(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return store.loadAndTouch(sessionId, ttl());
    }

    public void revoke(String sessionId, long userId) {
        if (sessionId != null && !sessionId.isBlank()) {
            store.delete(sessionId, userId);
        }
    }

    public void revokeAllForUser(long userId) {
        store.deleteAllForUser(userId);
    }

    public Duration ttl() {
        return Duration.ofMinutes(auth.sessionTtlMinutes());
    }

    public AppProperties.Auth config() {
        return auth;
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    /** 新会话的两个凭据：Cookie 里放 sessionId，前端拿 csrfToken 放请求头。 */
    public record IssuedSession(String sessionId, String csrfToken) {
    }
}
