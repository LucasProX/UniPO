package com.biecuoguo.activity.internal;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

/** 动态签到码：HMAC(活动密钥, 活动 ID + 30 秒时间片)，接受当前与上一个时间片，截图转发很快失效。 */
final class CheckinTokens {
    static final int SLOT_SECONDS = 30;
    static final Duration OPENS_BEFORE_START = Duration.ofMinutes(30);
    private static final SecureRandom RANDOM = new SecureRandom();

    private CheckinTokens() {}

    static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    static String newCode() {
        return String.format("%04d", RANDOM.nextInt(10_000));
    }

    static long slot(Instant now) {
        return now.getEpochSecond() / SLOT_SECONDS;
    }

    static int secondsLeftInSlot(Instant now) {
        return (int) (SLOT_SECONDS - now.getEpochSecond() % SLOT_SECONDS);
    }

    static String token(String secret, long activityId, long slot) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
            byte[] digest = mac.doFinal(("ck1:" + activityId + ":" + slot).getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 22);
        } catch (Exception impossible) {
            throw new IllegalStateException("签到码生成失败");
        }
    }

    static boolean validToken(String secret, long activityId, String candidate, Instant now) {
        if (secret == null || candidate == null || candidate.length() != 22) return false;
        long current = slot(now);
        return equal(token(secret, activityId, current), candidate) || equal(token(secret, activityId, current - 1), candidate);
    }

    static boolean validCode(String expected, String candidate) {
        return expected != null && candidate != null && candidate.trim().length() == 4 && equal(expected, candidate.trim());
    }

    static boolean windowOpen(LocalDateTime startsAt, LocalDateTime endsAt, LocalDateTime now) {
        return !now.isBefore(startsAt.minus(OPENS_BEFORE_START)) && now.isBefore(endsAt);
    }

    private static boolean equal(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }
}
