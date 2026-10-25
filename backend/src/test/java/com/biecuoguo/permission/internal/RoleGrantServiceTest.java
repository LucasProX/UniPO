package com.biecuoguo.permission.internal;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.biecuoguo.admin.api.AdminAuditApi;
import com.biecuoguo.domain.College;
import com.biecuoguo.domain.School;
import com.biecuoguo.domain.User;
import com.biecuoguo.error.StateConflictException;
import com.biecuoguo.mapper.CollegeMapper;
import com.biecuoguo.mapper.MajorMapper;
import com.biecuoguo.mapper.SchoolMapper;
import com.biecuoguo.mapper.UserMapper;
import com.biecuoguo.permission.api.Grant;
import com.biecuoguo.permission.api.Grants;
import com.biecuoguo.permission.api.Role;
import com.biecuoguo.permission.api.RoleGrantDtos;
import com.biecuoguo.permission.api.ScopeType;
import com.biecuoguo.security.CurrentUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RoleGrantServiceTest {
    private final RoleGrantMapper grants = mock(RoleGrantMapper.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final UserMapper users = mock(UserMapper.class);
    private final SchoolMapper schools = mock(SchoolMapper.class);
    private final CollegeMapper colleges = mock(CollegeMapper.class);
    private final AdminAuditApi audit = mock(AdminAuditApi.class);
    private final RoleGrantService service = new RoleGrantService(grants, permissions, users, schools, colleges,
            mock(MajorMapper.class), audit);
    private final CurrentUser admin = new CurrentUser(1L, "admin@unipo.invalid", "ADMIN", 1L, "s");
    private final CurrentUser operator = new CurrentUser(2L, "op@unipo.invalid", "SCHOOL_OPERATOR", 1L, "s");
    private User target;

    @BeforeAll
    static void lambdaMetadata() {
        for (Class<?> type : new Class<?>[]{RoleGrant.class, User.class}) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "grant-tests"), type);
        }
    }

    @BeforeEach
    void setUp() {
        target = new User();
        target.setId(9L);
        target.setPublicUid("uid9");
        target.setStatus("active");
        target.setRole("USER");
        when(users.selectOne(any())).thenReturn(target);
        when(users.lockById(9L)).thenReturn(target);
        when(users.selectById(9L)).thenReturn(target);
        when(permissions.grantsOf(admin)).thenReturn(new Grants(true, List.of()));
        when(permissions.grantsOf(operator)).thenReturn(new Grants(false,
                List.of(new Grant(5, Role.SCHOOL_OPERATOR, ScopeType.SCHOOL, 1L, 1L, null))));
        when(permissions.grantsOf(9L)).thenReturn(Grants.none());
        when(permissions.activeRows(eq(9L), any())).thenReturn(List.of());
        School school = new School();
        school.setId(1L);
        school.setName("示例大学");
        school.setStatus("active");
        when(schools.selectById(1L)).thenReturn(school);
        College college = new College();
        college.setId(10L);
        college.setSchoolId(1L);
        college.setName("计算机学院");
        when(colleges.selectById(10L)).thenReturn(college);
        when(users.selectBatchIds(any())).thenReturn(List.of(target));
        doAnswer(call -> { ((RoleGrant) call.getArgument(0)).setId(70L); return 1; }).when(grants).insert(any(RoleGrant.class));
    }

    private RoleGrantDtos.GrantRequest request(Role role, Long scopeId) {
        return new RoleGrantDtos.GrantRequest("uid9", role, scopeId, "试点负责人", null);
    }

    @Test
    void onlyPlatformAdminsCanGrantEvenIfTheyAreSchoolOperators() {
        assertThrows(AccessDeniedException.class, () -> service.grant(operator, request(Role.COLLEGE_OPERATOR, 10L)));
        verify(grants, never()).insert(any(RoleGrant.class));
    }

    @Test
    void grantTakesTheSchoolFromTheDictionaryAndDefaultsToSchoolYearEnd() {
        RoleGrantDtos.GrantView view = service.grant(admin, request(Role.COLLEGE_OPERATOR, 10L));
        ArgumentCaptor<RoleGrant> saved = ArgumentCaptor.forClass(RoleGrant.class);
        verify(grants).insert(saved.capture());
        assertEquals("COLLEGE_OPERATOR", saved.getValue().getRole());
        assertEquals("COLLEGE", saved.getValue().getScopeType());
        assertEquals(10L, saved.getValue().getScopeId());
        assertEquals(1L, saved.getValue().getSchoolId());
        assertEquals(8, saved.getValue().getExpiresAt().getMonthValue());
        assertEquals(31, saved.getValue().getExpiresAt().getDayOfMonth());
        assertEquals(1L, saved.getValue().getGrantedBy());
        verify(audit).recordRequired(eq(admin), eq("ROLE_GRANT"), eq("user"), eq(9L), contains("COLLEGE_OPERATOR"));
        assertEquals("院运营", view.roleLabel());
    }

    @Test
    void scopesMustExistAndMatchTheRoleLevel() {
        assertThrows(IllegalArgumentException.class, () -> service.grant(admin, request(Role.COLLEGE_OPERATOR, 99L)));
        assertThrows(IllegalArgumentException.class, () -> service.grant(admin, request(Role.SCHOOL_OPERATOR, null)));
        assertThrows(IllegalArgumentException.class, () -> service.grant(admin, request(Role.CONTENT_EDITOR, 1L)));
        verify(grants, never()).insert(any(RoleGrant.class));
    }

    @Test
    void duplicateActiveGrantIsAConflictAndInactiveUsersCannotBeGranted() {
        RoleGrant existing = new RoleGrant();
        existing.setRole("SCHOOL_OPERATOR");
        existing.setScopeId(1L);
        when(permissions.activeRows(eq(9L), any())).thenReturn(List.of(existing));
        assertThrows(StateConflictException.class, () -> service.grant(admin, request(Role.SCHOOL_OPERATOR, 1L)));
        target.setStatus("disabled");
        assertThrows(StateConflictException.class, () -> service.grant(admin, request(Role.MODERATOR, 1L)));
        verify(grants, never()).insert(any(RoleGrant.class));
    }

    @Test
    void expiryInThePastIsRejected() {
        var past = new RoleGrantDtos.GrantRequest("uid9", Role.MODERATOR, 1L, "x",
                java.time.OffsetDateTime.now().minusDays(1));
        assertThrows(IllegalArgumentException.class, () -> service.grant(admin, past));
    }

    @Test
    void revokingTwiceIsAConflict() {
        RoleGrant row = new RoleGrant();
        row.setId(7L);
        row.setUserId(9L);
        row.setRole("MODERATOR");
        row.setRevokedAt(LocalDateTime.now());
        when(grants.selectById(7L)).thenReturn(row);
        assertThrows(StateConflictException.class, () -> service.revoke(admin, 7L, new RoleGrantDtos.RevokeRequest("换届")));
        assertThrows(AccessDeniedException.class, () -> service.revoke(operator, 7L, new RoleGrantDtos.RevokeRequest("换届")));
    }

    @Test
    void legacyRolesMapLikeTheOldActivityRules() {
        assertEquals(Role.SCHOOL_OPERATOR, RoleGrantService.legacyRole("OPERATOR", "school"));
        assertEquals(Role.COLLEGE_OPERATOR, RoleGrantService.legacyRole("COLLEGE_OPERATOR", null));
        assertEquals(Role.MAJOR_OPERATOR, RoleGrantService.legacyRole("SCHOOL_OPERATOR", "major"));
        assertThrows(IllegalArgumentException.class, () -> RoleGrantService.legacyRole("OPERATOR", null));
    }

    @Test
    void schoolYearEndsOnAugust31() {
        assertEquals(LocalDateTime.of(2027, 8, 31, 23, 59, 59),
                RoleGrantService.schoolYearEnd(LocalDateTime.of(2026, 10, 25, 9, 0)));
        assertEquals(LocalDateTime.of(2027, 8, 31, 23, 59, 59),
                RoleGrantService.schoolYearEnd(LocalDateTime.of(2027, 8, 31, 9, 0)));
        assertEquals(LocalDateTime.of(2028, 8, 31, 23, 59, 59),
                RoleGrantService.schoolYearEnd(LocalDateTime.of(2027, 9, 1, 0, 0)));
    }
}
