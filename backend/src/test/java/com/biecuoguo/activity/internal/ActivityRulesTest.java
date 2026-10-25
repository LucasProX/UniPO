package com.biecuoguo.activity.internal;

import com.biecuoguo.permission.api.Role;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ActivityRulesTest {
    @Test
    void rewardsAreCappedByRoleAndNeverNegative() {
        // 发布权限与面向范围的矩阵测试在 permission 模块的 GrantsTest。
        var college = Role.COLLEGE_OPERATOR.rewardCap();
        assertDoesNotThrow(() -> ActivityRules.checkReward(college, 50, 100));
        assertThrows(IllegalArgumentException.class, () -> ActivityRules.checkReward(college, 51, 0));
        assertThrows(IllegalArgumentException.class, () -> ActivityRules.checkReward(Role.MAJOR_OPERATOR.rewardCap(), 0, 61));
        assertThrows(IllegalArgumentException.class, () -> ActivityRules.checkReward(Role.ADMIN_REWARD_CAP, -1, 0));
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
