package com.biecuoguo.auth.api;

import com.biecuoguo.security.CurrentUser;
import java.time.Instant;
import java.util.Optional;

/** Shared authority for Cookie and Bearer authentication. No positive cache of revoked identities. */
public interface AccountSecurityApi {
    record ValidatedIdentity(CurrentUser user, Instant expiresAt) {}

    /** Password/code verification must capture expected.authVersion before calling this method. */
    ValidatedIdentity createSession(CurrentUser expected);
    Optional<ValidatedIdentity> validate(CurrentUser credential);
    void revokeSession(String sessionId, long userId);
    void revokeAll(long userId);
}
