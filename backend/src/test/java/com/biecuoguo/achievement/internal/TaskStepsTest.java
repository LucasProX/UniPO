package com.biecuoguo.achievement.internal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskStepsTest {
    @Test
    void stepsAreOnePerLineWithBlanksDropped() {
        assertEquals(List.of("查看通知", "完成报名"), AchievementService.steps("  查看通知 \n\n完成报名\n  "));
        assertEquals(List.of("单步"), AchievementService.steps("单步"));
        assertEquals(List.of("Windows 换行"), AchievementService.steps("Windows 换行\r\n"));
        assertEquals(List.of(), AchievementService.steps(null));
        assertEquals(List.of(), AchievementService.steps("   "));
    }
}
