package com.biecuoguo.achievement.internal;

import com.biecuoguo.achievement.api.GrowthTerms;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrowthTermsTest {
    @Test
    void academicYearStartsInSeptemberAndJanuaryStaysInTheFirstTerm() {
        assertEquals(0, GrowthTerms.termIndex(2026, LocalDate.of(2026, 9, 1)));
        assertEquals(0, GrowthTerms.termIndex(2026, LocalDate.of(2027, 1, 20)));
        assertEquals(1, GrowthTerms.termIndex(2026, LocalDate.of(2027, 2, 15)));
        assertEquals(1, GrowthTerms.termIndex(2026, LocalDate.of(2027, 8, 31)));
        assertEquals(2, GrowthTerms.termIndex(2026, LocalDate.of(2027, 9, 1)));
        assertEquals(-1, GrowthTerms.termIndex(2026, LocalDate.of(2026, 7, 1)));
        assertEquals(7, GrowthTerms.termIndex(2023, LocalDate.of(2027, 5, 1)));
    }

    @Test
    void labelsCoverBeforeEnrollmentAndAfterGraduation() {
        assertEquals("入学前", GrowthTerms.termLabel(-1));
        assertEquals("大一上学期", GrowthTerms.termLabel(0));
        assertEquals("大二下学期", GrowthTerms.termLabel(3));
        assertEquals("已毕业", GrowthTerms.termLabel(8));
        assertEquals("准大一", GrowthTerms.gradeLabel(-1));
        assertEquals("大三", GrowthTerms.gradeLabel(5));
        assertEquals("已毕业", GrowthTerms.gradeLabel(9));
    }

    @Test
    void entryYearMustBeRecentOrNext() {
        LocalDate today = LocalDate.of(2026, 10, 23);
        assertTrue(GrowthTerms.plausibleEntryYear(2027, today));
        assertTrue(GrowthTerms.plausibleEntryYear(2016, today));
        assertFalse(GrowthTerms.plausibleEntryYear(2028, today));
        assertFalse(GrowthTerms.plausibleEntryYear(2015, today));
    }

    @Test
    void stagesFollowTheirTermRangesWithOpenEndedSenior() {
        List<AchievementStage> stages = List.of(stage("freshman-1", 0, 0), stage("freshman-2", 1, 1),
                stage("sophomore", 2, 3), stage("junior", 4, 5), stage("senior", 6, null));
        assertEquals("freshman-1", AchievementService.stageForTerm(stages, -2));
        assertEquals("freshman-1", AchievementService.stageForTerm(stages, 0));
        assertEquals("freshman-2", AchievementService.stageForTerm(stages, 1));
        assertEquals("sophomore", AchievementService.stageForTerm(stages, 3));
        assertEquals("junior", AchievementService.stageForTerm(stages, 4));
        assertEquals("senior", AchievementService.stageForTerm(stages, 12));
    }

    private static AchievementStage stage(String code, Integer from, Integer to) {
        AchievementStage stage = new AchievementStage();
        stage.setCode(code);
        stage.setTermFrom(from);
        stage.setTermTo(to);
        return stage;
    }

    @Test
    void termStartsAreSeptemberAndFebruaryWithJanuaryInTheAutumnTerm() {
        assertEquals(LocalDate.of(2026, 9, 1), GrowthTerms.termStartOf(LocalDate.of(2026, 10, 23)));
        assertEquals(LocalDate.of(2026, 9, 1), GrowthTerms.termStartOf(LocalDate.of(2027, 1, 31)));
        assertEquals(LocalDate.of(2027, 2, 1), GrowthTerms.termStartOf(LocalDate.of(2027, 2, 1)));
        assertEquals(LocalDate.of(2027, 2, 1), GrowthTerms.termStartOf(LocalDate.of(2027, 8, 31)));
        assertEquals(LocalDate.of(2027, 2, 1), GrowthTerms.nextTermStart(LocalDate.of(2026, 9, 1)));
        assertEquals(LocalDate.of(2027, 9, 1), GrowthTerms.nextTermStart(LocalDate.of(2027, 2, 1)));
        assertEquals(LocalDate.of(2026, 2, 1), GrowthTerms.previousTermStart(LocalDate.of(2026, 9, 1)));
        assertEquals(LocalDate.of(2025, 9, 1), GrowthTerms.previousTermStart(LocalDate.of(2026, 2, 1)));
        assertTrue(GrowthTerms.isTermStart(LocalDate.of(2026, 2, 1)));
        assertFalse(GrowthTerms.isTermStart(LocalDate.of(2026, 3, 1)));
        assertFalse(GrowthTerms.isTermStart(LocalDate.of(2026, 9, 2)));
    }

    @Test
    void termLabelFallsBackToSeasonWithoutEntryYear() {
        assertEquals("大一上学期", GrowthTerms.termLabel(2026, LocalDate.of(2026, 9, 1)));
        assertEquals("大二下学期", GrowthTerms.termLabel(2025, LocalDate.of(2027, 2, 1)));
        assertEquals("2026 秋季学期", GrowthTerms.termLabel(null, LocalDate.of(2026, 9, 1)));
        assertEquals("2027 春季学期", GrowthTerms.termLabel(null, LocalDate.of(2027, 2, 1)));
    }

    @Test
    void attendanceBadgesAreReachedExactlyAtTheirThreshold() {
        assertEquals(java.util.Optional.of("activity-1"), com.biecuoguo.achievement.api.AttendanceBadges.reachedAt(1));
        assertEquals(java.util.Optional.of("activity-5"), com.biecuoguo.achievement.api.AttendanceBadges.reachedAt(5));
        assertTrue(com.biecuoguo.achievement.api.AttendanceBadges.reachedAt(2).isEmpty());
        assertEquals(15, com.biecuoguo.achievement.api.AttendanceBadges.THRESHOLDS.values().stream()
                .mapToInt(Integer::intValue).max().orElseThrow());
    }
}
