package com.biecuoguo.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExperienceServiceTest {
    @Test
    void awardsUseTheSameCumulativeThresholdAsCheckInProgression() {
        assertEquals(new ExperienceService.Progress(2, 8, "萌新探路员"),
                ExperienceService.progress(1, 544, 12));
        assertEquals(new ExperienceService.Progress(3, 0, "萌新探路员"),
                ExperienceService.progress(2, 600, 4));
    }

    @Test
    void capsLegacyLevelsAndRejectsExperienceOverflow() {
        assertEquals(new ExperienceService.Progress(100, Integer.MAX_VALUE, "传说中的不鸽王"),
                ExperienceService.progress(100, Integer.MAX_VALUE, 0));
        assertThrows(IllegalArgumentException.class,
                () -> ExperienceService.progress(100, Integer.MAX_VALUE, 1));
        assertThrows(IllegalArgumentException.class,
                () -> ExperienceService.progress(1, 0, -1));
    }
}
