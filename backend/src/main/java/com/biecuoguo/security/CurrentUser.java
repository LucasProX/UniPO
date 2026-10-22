package com.biecuoguo.security;

public record CurrentUser(Long id, String email, String role, Long authVersion, String sessionId) {
    /** Non-credential principals used by internal business callers; never sufficient for authentication. */
    public CurrentUser(Long id, String email, String role) { this(id, email, role, null, null); }
    @Override public String toString() { return "CurrentUser[id=" + id + ", role=" + role + "]"; }
}
