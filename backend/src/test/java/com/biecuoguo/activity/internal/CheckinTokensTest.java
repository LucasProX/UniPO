package com.biecuoguo.activity.internal;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckinTokensTest {
    private final String secret = CheckinTokens.newSecret();

    @Test
    void tokenRotatesEveryThirtySecondsAndAcceptsOnlyTheCurrentAndPreviousSlot() {
        Instant now = Instant.ofEpochSecond(1_800_000_000L);
        String current = CheckinTokens.token(secret, 7, CheckinTokens.slot(now));
        assertEquals(22, current.length());
        assertTrue(CheckinTokens.validToken(secret, 7, current, now));
        assertTrue(CheckinTokens.validToken(secret, 7, current, now.plusSeconds(30)));
        assertFalse(CheckinTokens.validToken(secret, 7, current, now.plusSeconds(60)));
        assertFalse(CheckinTokens.validToken(secret, 8, current, now));
        assertFalse(CheckinTokens.validToken(CheckinTokens.newSecret(), 7, current, now));
        assertNotEquals(current, CheckinTokens.token(secret, 7, CheckinTokens.slot(now) + 1));
    }

    @Test
    void codeIsFourDigitsAndComparedExactly() {
        String code = CheckinTokens.newCode();
        assertTrue(code.matches("\\d{4}"));
        assertTrue(CheckinTokens.validCode("0420", " 0420 "));
        assertFalse(CheckinTokens.validCode("0420", "420"));
        assertFalse(CheckinTokens.validCode("0420", null));
    }

    @Test
    void windowOpensThirtyMinutesBeforeStartAndClosesAtEnd() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = start.plusHours(2);
        assertFalse(CheckinTokens.windowOpen(start, end, start.minusMinutes(31)));
        assertTrue(CheckinTokens.windowOpen(start, end, start.minusMinutes(30)));
        assertTrue(CheckinTokens.windowOpen(start, end, end.minusSeconds(1)));
        assertFalse(CheckinTokens.windowOpen(start, end, end));
    }
}
