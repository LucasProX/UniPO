package com.biecuoguo.security;

import java.time.Instant;

/** An immutable credential reference; never put this object in logs or broker payloads. */
public record AuthenticationContext(CurrentUser user, Instant expiresAt, String cookieSessionId) {
    @Override public String toString() { return "AuthenticationContext[userId=" + user.id() + "]"; }
}
