package com.biecuoguo.growth.internal;

import com.biecuoguo.activity.api.ActivityParticipationApi;
import com.biecuoguo.domain.User;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrowthPlanRulesTest {
    private static final LocalDate CURRENT = LocalDate.of(2026, 9, 1);

    @Test
    void personalTasksOnlyGoIntoTheCurrentOrNextTerm() {
        assertTrue(GrowthPlanService.canAddTo(CURRENT, CURRENT));
        assertTrue(GrowthPlanService.canAddTo(LocalDate.of(2027, 2, 1), CURRENT));
        assertFalse(GrowthPlanService.canAddTo(LocalDate.of(2026, 2, 1), CURRENT));
        assertFalse(GrowthPlanService.canAddTo(LocalDate.of(2027, 9, 1), CURRENT));
    }

    @Test
    void historyStartsAtEnrollmentOrEightTermsBack() {
        User withEntry = new User();
        withEntry.setEntryYear(2024);
        assertEquals(LocalDate.of(2024, 9, 1), GrowthPlanService.earliestTerm(withEntry, CURRENT));

        User future = new User();
        future.setEntryYear(2027);
        assertEquals(CURRENT, GrowthPlanService.earliestTerm(future, CURRENT));

        assertEquals(LocalDate.of(2022, 9, 1), GrowthPlanService.earliestTerm(new User(), CURRENT));
    }

    @Test
    void rejectedCancelledAndRevokedItemsDoNotCountTowardProgress() {
        assertTrue(GrowthPlanService.countsTowardPlan(item("PUBLISHED", "REGISTERED", false, false)));
        assertTrue(GrowthPlanService.countsTowardPlan(item("PUBLISHED", "WAITLISTED", false, false)));
        assertTrue(GrowthPlanService.countsTowardPlan(item("CANCELLED", "REGISTERED", true, false)));
        assertFalse(GrowthPlanService.countsTowardPlan(item("CANCELLED", "REGISTERED", false, false)));
        assertFalse(GrowthPlanService.countsTowardPlan(item("PUBLISHED", "REJECTED", false, false)));
        assertFalse(GrowthPlanService.countsTowardPlan(item("PUBLISHED", "REGISTERED", false, true)));
    }

    private static ActivityParticipationApi.Participation item(String activityStatus, String registrationStatus,
                                                               boolean checkedIn, boolean revoked) {
        OffsetDateTime at = OffsetDateTime.parse("2026-10-23T14:00:00+08:00");
        return new ActivityParticipationApi.Participation(1, "讲座", "LECTURE", "图书馆", "学院", at, at.plusHours(2),
                activityStatus, registrationStatus, checkedIn, checkedIn ? at : null, revoked, 0, 0);
    }
}
