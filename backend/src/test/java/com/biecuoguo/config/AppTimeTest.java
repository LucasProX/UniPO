package com.biecuoguo.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 时区边界：数据库裸时间 <-> 接口带偏移时间，只走 app.time-zone。 */
class AppTimeTest {

    @AfterEach
    void resetZone() {
        new AppTime("Asia/Shanghai");
    }

    @Test
    void toOffsetUsesConfiguredZone() {
        new AppTime("UTC");
        OffsetDateTime result = AppTime.toOffset(LocalDateTime.of(2026, 1, 1, 8, 0));
        assertEquals(ZoneOffset.UTC, result.getOffset());
        assertEquals(8, result.getHour());
    }

    @Test
    void toLocalConvertsAcrossZones() {
        new AppTime("Asia/Shanghai");
        LocalDateTime local = AppTime.toLocal(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        assertEquals(LocalDateTime.of(2026, 1, 1, 8, 0), local);
    }

    @Test
    void roundTripsWithinOneZone() {
        new AppTime("Asia/Shanghai");
        LocalDateTime original = LocalDateTime.of(2026, 6, 15, 23, 30);
        assertEquals(original, AppTime.toLocal(AppTime.toOffset(original)));
    }

    @Test
    void nullSafe() {
        assertNull(AppTime.toOffset(null));
        assertNull(AppTime.toLocal(null));
    }
}
