package com.biecuoguo.auth.internal;

import com.biecuoguo.auth.api.AccountSecurityApi;
import com.biecuoguo.config.AppProperties;
import com.biecuoguo.security.CurrentUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/** The database is the common revocation authority for every authentication carrier. */
@Service
public class AccountSecurityService implements AccountSecurityApi {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final DeviceSessionMapper mapper;
    private final Clock clock;
    private final long lifetimeMillis;

    @Autowired
    public AccountSecurityService(DeviceSessionMapper mapper, AppProperties properties) {
        this(mapper, properties, Clock.systemUTC());
    }

    AccountSecurityService(DeviceSessionMapper mapper, AppProperties properties, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
        if (properties.jwt() == null || properties.jwt().refreshExpirationMinutes() <= 0) {
            throw new IllegalArgumentException("设备会话有效期必须为正数");
        }
        this.lifetimeMillis = Math.multiplyExact(properties.jwt().refreshExpirationMinutes(), 60_000L);
    }

    @Override
    @Transactional
    public ValidatedIdentity createSession(CurrentUser expected) {
        if (!hasVersionedUser(expected)) {
            throw invalidIdentity();
        }
        long now = clock.millis();
        long expiresAt = Math.addExact(now, lifetimeMillis);
        String sessionId = newSessionId();
        int inserted = mapper.insertIfCurrent(sessionId, expected.id(), expected.authVersion(), now, expiresAt);
        if (inserted != 1) {
            throw invalidIdentity();
        }
        DeviceSessionRecord row = mapper.findValid(sessionId, expected.id(), expected.authVersion(), clock.millis());
        if (row == null) {
            throw invalidIdentity();
        }
        return identity(row);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Optional<ValidatedIdentity> validate(CurrentUser credential) {
        if (!hasVersionedUser(credential) || !validSessionId(credential.sessionId())) {
            return Optional.empty();
        }
        // Do not join a business transaction's old snapshot or retain a positive MyBatis cache entry.
        DeviceSessionRecord row = mapper.findValid(credential.sessionId(), credential.id(),
                credential.authVersion(), clock.millis());
        return Optional.ofNullable(row).map(AccountSecurityService::identity);
    }

    @Override
    @Transactional
    public void revokeSession(String sessionId, long userId) {
        if (userId > 0 && validSessionId(sessionId)) {
            mapper.revokeOne(sessionId, userId, clock.millis());
        }
    }

    @Override
    @Transactional
    public void revokeAll(long userId) {
        if (userId > 0 && mapper.advanceUserVersion(userId) == 1) {
            // Version advancement is authoritative; marking the old rows also records the operation.
            mapper.revokeOlderVersions(userId, clock.millis());
        }
    }

    private static ValidatedIdentity identity(DeviceSessionRecord row) {
        CurrentUser user = new CurrentUser(row.userId(), row.email(), row.role(), row.authVersion(), row.sessionId());
        return new ValidatedIdentity(user, Instant.ofEpochMilli(row.expiresAtMs()));
    }

    private static boolean hasVersionedUser(CurrentUser user) {
        return user != null && user.id() != null && user.id() > 0
                && user.authVersion() != null && user.authVersion() >= 0;
    }

    private static boolean validSessionId(String sessionId) {
        return sessionId != null && sessionId.matches("[A-Za-z0-9_-]{43}");
    }

    private static String newSessionId() {
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    private static InsufficientAuthenticationException invalidIdentity() {
        return new InsufficientAuthenticationException("登录状态已失效，请重新登录");
    }
}
