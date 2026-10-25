package com.biecuoguo.permission.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 越权矩阵：角色 × 能力 × 目标范围。学校 1 下有学院 10（专业 100、101）和学院 11（专业 110）；学校 2 下有学院 20。 */
class GrantsTest {
    private static final ScopeTarget SCHOOL_1 = ScopeTarget.school(1);
    private static final ScopeTarget COLLEGE_10 = ScopeTarget.college(1, 10);
    private static final ScopeTarget MAJOR_100 = ScopeTarget.major(1, 10L, 100);
    private static final ScopeTarget MAJOR_101 = ScopeTarget.major(1, 10L, 101);
    private static final ScopeTarget COLLEGE_11 = ScopeTarget.college(1, 11);
    private static final ScopeTarget MAJOR_110 = ScopeTarget.major(1, 11L, 110);
    private static final ScopeTarget SCHOOL_2 = ScopeTarget.school(2);
    private static final ScopeTarget COLLEGE_20 = ScopeTarget.college(2, 20);

    private static Grants of(Role role, ScopeType type, Long scopeId, Long schoolId) {
        return new Grants(false, List.of(new Grant(1, role, type, scopeId, schoolId, null)));
    }

    @Test
    void schoolOperatorCoversItsWholeSchoolAndNothingElse() {
        Grants g = of(Role.SCHOOL_OPERATOR, ScopeType.SCHOOL, 1L, 1L);
        for (ScopeTarget inside : List.of(SCHOOL_1, COLLEGE_10, MAJOR_100, COLLEGE_11, MAJOR_110)) {
            assertTrue(g.can(Capability.POST_MODERATE, inside), inside.toString());
            assertTrue(g.can(Capability.ACTIVITY_PUBLISH, inside), inside.toString());
        }
        assertFalse(g.can(Capability.POST_MODERATE, SCHOOL_2));
        assertFalse(g.can(Capability.ACTIVITY_MANAGE, COLLEGE_20));
    }

    @Test
    void collegeOperatorStaysInsideItsCollege() {
        Grants g = of(Role.COLLEGE_OPERATOR, ScopeType.COLLEGE, 10L, 1L);
        assertTrue(g.can(Capability.ACTIVITY_PUBLISH, COLLEGE_10));
        assertTrue(g.can(Capability.ACTIVITY_PUBLISH, MAJOR_100));
        assertTrue(g.can(Capability.POST_MODERATE, MAJOR_101));
        assertFalse(g.can(Capability.ACTIVITY_PUBLISH, SCHOOL_1), "院运营不能面向全校");
        assertFalse(g.can(Capability.ACTIVITY_PUBLISH, COLLEGE_11), "院运营不能面向别的学院");
        assertFalse(g.can(Capability.ACTIVITY_PUBLISH, MAJOR_110));
        assertFalse(g.can(Capability.POST_FEATURE, COLLEGE_10), "院运营不能置顶");
        // 同一个学院 id 出现在另一所学校的目标上也不算（防止 id 碰撞）。
        assertFalse(g.can(Capability.ACTIVITY_PUBLISH, new ScopeTarget(2, 10L, null)));
    }

    @Test
    void majorOperatorOnlyReachesItsMajor() {
        Grants g = of(Role.MAJOR_OPERATOR, ScopeType.MAJOR, 100L, 1L);
        assertTrue(g.can(Capability.ACTIVITY_PUBLISH, MAJOR_100));
        assertFalse(g.can(Capability.ACTIVITY_PUBLISH, MAJOR_101));
        assertFalse(g.can(Capability.ACTIVITY_PUBLISH, COLLEGE_10));
        assertFalse(g.can(Capability.POST_MODERATE, MAJOR_100), "专业运营不能下架帖子");
    }

    @Test
    void moderatorCanHandleReportsButNotPublish() {
        Grants g = of(Role.MODERATOR, ScopeType.SCHOOL, 1L, 1L);
        assertTrue(g.can(Capability.POST_MODERATE, MAJOR_110));
        assertTrue(g.can(Capability.REPORT_HANDLE, SCHOOL_1));
        assertFalse(g.can(Capability.ACTIVITY_PUBLISH, SCHOOL_1));
        assertFalse(g.can(Capability.POST_OFFICIAL, SCHOOL_1));
        assertTrue(g.rewardCap(SCHOOL_1).isEmpty());
        assertTrue(g.primaryPublisher().isEmpty());
    }

    @Test
    void contentEditorIsPlatformWideButOnlyForContent() {
        Grants g = of(Role.CONTENT_EDITOR, ScopeType.PLATFORM, null, null);
        assertTrue(g.can(Capability.GUIDE_MANAGE, SCHOOL_2));
        assertTrue(g.can(Capability.POST_VALIDITY, MAJOR_100));
        assertFalse(g.can(Capability.POST_MODERATE, SCHOOL_1));
        assertFalse(g.can(Capability.ACTIVITY_PUBLISH, SCHOOL_1));
    }

    @Test
    void nobodyAndAdminAreTheTwoEnds() {
        assertFalse(Grants.none().any());
        assertFalse(Grants.none().can(Capability.POST_MODERATE, SCHOOL_1));
        Grants admin = new Grants(true, List.of());
        assertTrue(admin.any());
        assertTrue(admin.can(Capability.ACTIVITY_MANAGE, COLLEGE_20));
        assertEquals(Role.ADMIN_REWARD_CAP, admin.rewardCap(MAJOR_110).orElseThrow());
    }

    @Test
    void rewardCapTakesTheHighestGrantThatCoversTheTarget() {
        Grants g = new Grants(false, List.of(
                new Grant(1, Role.MAJOR_OPERATOR, ScopeType.MAJOR, 100L, 1L, null),
                new Grant(2, Role.COLLEGE_OPERATOR, ScopeType.COLLEGE, 10L, 1L, null)));
        assertEquals(Role.COLLEGE_OPERATOR.rewardCap(), g.rewardCap(MAJOR_100).orElseThrow());
        assertEquals(Role.COLLEGE_OPERATOR.rewardCap(), g.rewardCap(COLLEGE_10).orElseThrow());
        assertTrue(g.rewardCap(SCHOOL_1).isEmpty());
        assertEquals(Role.COLLEGE_OPERATOR, g.primaryPublisher().orElseThrow().role());
    }

    @Test
    void displayRoleOnlyForOperators() {
        assertEquals("SCHOOL_OPERATOR", Role.SCHOOL_OPERATOR.displayRole());
        assertNull(Role.MODERATOR.displayRole());
        assertNull(Role.CONTENT_EDITOR.displayRole());
        for (Role role : Role.values()) {
            if (role.has(Capability.ACTIVITY_PUBLISH)) assertNotNull(role.rewardCap(), role.name());
        }
    }
}
