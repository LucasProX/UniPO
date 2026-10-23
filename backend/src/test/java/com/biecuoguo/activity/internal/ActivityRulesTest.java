package com.biecuoguo.activity.internal;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ActivityRulesTest {
    @Test
    void onlyAdminsAndScopedOperatorsCanPublish() {
        assertEquals(ActivityRules.Level.ADMIN, ActivityRules.levelOf("ADMIN", null));
        assertEquals(ActivityRules.Level.COLLEGE, ActivityRules.levelOf("COLLEGE_OPERATOR", "college"));
        assertEquals(ActivityRules.Level.SCHOOL, ActivityRules.levelOf("OPERATOR", "school"));
        assertNull(ActivityRules.levelOf("USER", "school"));
        assertNull(ActivityRules.levelOf("OPERATOR", null));
    }

    @Test
    void organizersStayInsideTheirOwnScope() {
        var college = ActivityRules.Level.COLLEGE;
        assertThrows(IllegalArgumentException.class,
                () -> ActivityRules.checkAudience(college, 1L, null, "SCHOOL", null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> ActivityRules.checkAudience(college, 1L, null, "COLLEGE", 2L, null, null));
        assertDoesNotThrow(() -> ActivityRules.checkAudience(college, 1L, null, "COLLEGE", 1L, null, null));
        assertDoesNotThrow(() -> ActivityRules.checkAudience(college, 1L, null, "MAJOR", 1L, 7L, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> ActivityRules.checkAudience(college, 1L, null, "MAJOR", 3L, 9L, 3L));
        var major = ActivityRules.Level.MAJOR;
        assertThrows(IllegalArgumentException.class,
                () -> ActivityRules.checkAudience(major, 1L, 7L, "MAJOR", 1L, 8L, 1L));
        assertDoesNotThrow(() -> ActivityRules.checkAudience(ActivityRules.Level.SCHOOL, null, null, "MAJOR", 3L, 9L, 3L));
    }

    @Test
    void rewardsAreCappedByLevelAndNeverNegative() {
        assertDoesNotThrow(() -> ActivityRules.checkReward(ActivityRules.Level.COLLEGE, 50, 100));
        assertThrows(IllegalArgumentException.class, () -> ActivityRules.checkReward(ActivityRules.Level.COLLEGE, 51, 0));
        assertThrows(IllegalArgumentException.class, () -> ActivityRules.checkReward(ActivityRules.Level.MAJOR, 0, 61));
        assertThrows(IllegalArgumentException.class, () -> ActivityRules.checkReward(ActivityRules.Level.ADMIN, -1, 0));
    }

    @Test
    void phaseFollowsTimeAndCancellation() {
        LocalDateTime deadline = LocalDateTime.of(2026, 11, 1, 12, 0);
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        assertEquals("OPEN", ActivityRules.phase("PUBLISHED", deadline, start, end, deadline.minusMinutes(1)));
        assertEquals("CLOSED", ActivityRules.phase("PUBLISHED", deadline, start, end, deadline));
        assertEquals("ONGOING", ActivityRules.phase("PUBLISHED", deadline, start, end, start));
        assertEquals("ENDED", ActivityRules.phase("PUBLISHED", deadline, start, end, end));
        assertEquals("CANCELLED", ActivityRules.phase("CANCELLED", deadline, start, end, deadline.minusDays(1)));
    }

    @Test
    void eligibilityChecksSchoolScopeAndEntryYear() {
        Activity activity = new Activity();
        activity.setSchoolId(1L);
        activity.setAudienceScope("MAJOR");
        activity.setAudienceMajorId(7L);
        activity.setAudienceEntryYear(2025);
        assertEquals("仅限本校同学报名", ActivityRules.ineligibleReason(activity, 2L, 1L, 7L, 2025, "软件工程"));
        assertEquals("仅限软件工程报名，可在「成长」里补充学院和专业",
                ActivityRules.ineligibleReason(activity, 1L, 1L, 8L, 2025, "软件工程"));
        assertEquals("仅限 2025 级同学报名", ActivityRules.ineligibleReason(activity, 1L, 1L, 7L, 2026, "软件工程"));
        assertNull(ActivityRules.ineligibleReason(activity, 1L, 1L, 7L, 2025, "软件工程"));
    }
}
