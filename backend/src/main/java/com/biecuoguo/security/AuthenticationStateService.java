package com.biecuoguo.security;

import com.biecuoguo.auth.api.AccountSecurityApi;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Authentication boundary shared by HTTP and realtime. Unknown state never grants access. */
@Service
public class AuthenticationStateService {
    private final JwtService jwt;
    private final TokenBlacklistService blacklist;
    private final AccountSecurityApi accounts;
    private final SessionService sessions;

    public AuthenticationStateService(JwtService jwt, TokenBlacklistService blacklist, AccountSecurityApi accounts,
                                      SessionService sessions) {
        this.jwt = jwt; this.blacklist = blacklist; this.accounts = accounts; this.sessions = sessions;
    }

    public Optional<AuthenticationContext> authenticateAccess(String token) {
        try {
            if (token == null || token.isBlank() || blacklist.isRevoked(token)) { return Optional.empty(); }
            CurrentUser claims = jwt.parse(token);
            return accounts.validate(claims).map(valid -> new AuthenticationContext(valid.user(),
                    jwt.expiresAt(token).isBefore(valid.expiresAt()) ? jwt.expiresAt(token) : valid.expiresAt(), null));
        } catch (RuntimeException ex) { return Optional.empty(); }
    }

    public Optional<AuthenticationContext> authenticateCookie(String sessionId) {
        try {
            return sessions.peek(sessionId).flatMap(data -> accounts.validate(new CurrentUser(data.userId(), data.email(),
                            data.role(), data.authVersion(), data.deviceSessionId())))
                    .map(valid -> new AuthenticationContext(valid.user(), valid.expiresAt(), sessionId));
        } catch (RuntimeException ex) { return Optional.empty(); }
    }

    public boolean isValid(AuthenticationContext context) {
        try {
            if (context == null || context.expiresAt() == null || !context.expiresAt().isAfter(Instant.now())) { return false; }
            if (context.cookieSessionId() != null) {
                return sessions.peek(context.cookieSessionId()).filter(data -> data.userId() == context.user().id()
                        && Objects.equals(data.authVersion(), context.user().authVersion())
                        && Objects.equals(data.deviceSessionId(), context.user().sessionId())).isPresent();
            }
            return accounts.validate(context.user()).isPresent();
        } catch (RuntimeException ex) { return false; }
    }
}
