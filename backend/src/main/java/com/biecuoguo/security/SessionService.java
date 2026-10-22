package com.biecuoguo.security;

import com.biecuoguo.config.AppProperties;
import com.biecuoguo.auth.api.AccountSecurityApi;
import com.biecuoguo.state.SessionStore;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.transaction.annotation.Transactional;
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
    private final AccountSecurityApi accountSecurity;

    public SessionService(SessionStore store, AppProperties properties, AccountSecurityApi accountSecurity) {
        this.store = store;
        this.auth = properties.auth();
        this.accountSecurity = accountSecurity;
    }

    public IssuedSession create(CurrentUser user) {
        accountSecurity.validate(user).orElseThrow(() -> new InsufficientAuthenticationException("登录身份已失效"));
        String sessionId = randomToken();
        String csrfToken = randomToken();
        store.save(sessionId, new SessionStore.SessionData(user.id(), user.role(), user.email(), csrfToken,
                user.authVersion(), user.sessionId()), ttl());
        return new IssuedSession(sessionId, csrfToken);
    }

    public Optional<SessionStore.SessionData> resolve(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return validate(store.loadAndTouch(sessionId, ttl()));
    }

    public Optional<SessionStore.SessionData> peek(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) { return Optional.empty(); }
        return validate(store.load(sessionId));
    }

    private Optional<SessionStore.SessionData> validate(Optional<SessionStore.SessionData> stored) {
        return stored.flatMap(data -> accountSecurity.validate(new CurrentUser(data.userId(), data.email(), data.role(),
                        data.authVersion(), data.deviceSessionId()))
                .map(valid -> new SessionStore.SessionData(valid.user().id(), valid.user().role(), valid.user().email(),
                        data.csrfToken(), valid.user().authVersion(), valid.user().sessionId())));
    }

    public void revoke(String sessionId, long userId) {
        if (sessionId != null && !sessionId.isBlank()) {
            store.delete(sessionId, userId);
        }
    }

    @Transactional
    public void revokeAllForUser(long userId) {
        accountSecurity.revokeAll(userId);
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
