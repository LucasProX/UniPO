package com.biecuoguo.push.api;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/** Safe delivery outcome for durable retry; never carries provider payloads, device tokens or causes. */
public final class PushDeliveryException extends RuntimeException {
    private static final Set<String> CODES = Set.of(
            "APNS_SERVER_ERROR", "APNS_RATE_LIMITED", "APNS_REJECTED", "APNS_UNAVAILABLE",
            "APNS_TIMEOUT", "APNS_INVALID_PAYLOAD", "APNS_MORE_TARGETS");

    private final String code;
    private final boolean retryable;
    private final Duration retryAfter;

    public PushDeliveryException(String code, boolean retryable, Duration retryAfter) {
        super(checkedCode(code), null, false, false);
        this.code = code;
        this.retryable = retryable;
        this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
        if (retryAfter.isNegative() || retryAfter.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Push retry delay is outside the supported range");
        }
    }

    public String code() { return code; }

    public boolean retryable() { return retryable; }

    public Duration retryAfter() { return retryAfter; }

    private static String checkedCode(String code) {
        if (code == null || !CODES.contains(code)) { throw new IllegalArgumentException("Unknown push delivery code"); }
        return code;
    }
}
