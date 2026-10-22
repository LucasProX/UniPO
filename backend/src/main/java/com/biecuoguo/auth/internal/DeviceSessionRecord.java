package com.biecuoguo.auth.internal;

/** A fresh join result, never a cached authentication decision or a public API DTO. */
public record DeviceSessionRecord(Long userId, String email, String role, Long authVersion,
                                  String sessionId, long expiresAtMs) {}
