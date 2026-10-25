package com.biecuoguo.permission.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.biecuoguo.admin.api.AdminAuditApi;
import com.biecuoguo.config.AppTime;
import com.biecuoguo.domain.College;
import com.biecuoguo.domain.Major;
import com.biecuoguo.domain.School;
import com.biecuoguo.domain.User;
import com.biecuoguo.error.StateConflictException;
import com.biecuoguo.mapper.CollegeMapper;
import com.biecuoguo.mapper.MajorMapper;
import com.biecuoguo.mapper.SchoolMapper;
import com.biecuoguo.mapper.UserMapper;
import com.biecuoguo.permission.api.Capability;
import com.biecuoguo.permission.api.Grant;
import com.biecuoguo.permission.api.Grants;
import com.biecuoguo.permission.api.Role;
import com.biecuoguo.permission.api.RoleGrantApi;
import com.biecuoguo.permission.api.RoleGrantDtos;
import com.biecuoguo.permission.api.ScopeType;
import com.biecuoguo.security.CurrentUser;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 授予、撤销与查询范围授权。第一期只有平台管理员能授予和撤销；每次写入都和必需审计在同一事务里。
 *
 * <p>{@code users.role / operator_scope} 之后只是展示缓存（帖子作者旁的认证标识、旧客户端字段），
 * 由这里在授权变化和到期时重算，任何权限判断都不再读它们——平台管理员 {@code ADMIN} 除外。
 */
@Service
public class RoleGrantService implements RoleGrantApi {
    static final Set<String> DISPLAY_ROLES = Set.of("SCHOOL_OPERATOR", "COLLEGE_OPERATOR", "MAJOR_OPERATOR");

    private final RoleGrantMapper grantMapper;
    private final PermissionService permissions;
    private final UserMapper userMapper;
    private final SchoolMapper schoolMapper;
    private final CollegeMapper collegeMapper;
    private final MajorMapper majorMapper;
    private final AdminAuditApi audit;

    public RoleGrantService(RoleGrantMapper grantMapper, PermissionService permissions, UserMapper userMapper,
                            SchoolMapper schoolMapper, CollegeMapper collegeMapper, MajorMapper majorMapper,
                            AdminAuditApi audit) {
        this.grantMapper = grantMapper;
        this.permissions = permissions;
        this.userMapper = userMapper;
        this.schoolMapper = schoolMapper;
        this.collegeMapper = collegeMapper;
        this.majorMapper = majorMapper;
        this.audit = audit;
    }

    // ---- 授予与撤销 ----

    @Transactional
    public RoleGrantDtos.GrantView grant(CurrentUser actor, RoleGrantDtos.GrantRequest request) {
        requireAdmin(actor);
        User target = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPublicUid, request.userUid().trim()));
        if (target == null) throw new NoSuchElementException("用户不存在");
        // 锁用户行：同一个人的授权变更串行，重复授予的检查不会被并发绕过。
        target = userMapper.lockById(target.getId());
        if (!"active".equals(target.getStatus())) throw new StateConflictException("账号不可用，不能授予角色");
        Scope scope = resolveScope(request.role(), request.scopeId());
        LocalDateTime now = AppTime.nowLocal().truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime expires = request.expiresAt() == null ? schoolYearEnd(now)
                : AppTime.toLocal(request.expiresAt()).truncatedTo(ChronoUnit.SECONDS);
        if (!expires.isAfter(now)) throw new IllegalArgumentException("到期时间必须晚于现在");
        boolean duplicate = permissions.activeRows(target.getId(), now).stream().anyMatch(row ->
                row.getRole().equals(request.role().name()) && Objects.equals(row.getScopeId(), scope.id()));
        if (duplicate) throw new StateConflictException("此人已经有这个范围的同一角色");
        RoleGrant row = insert(target.getId(), request.role(), scope, actor.id(), request.reason().trim(), now, expires);
        audit.recordRequired(actor, "ROLE_GRANT", "user", target.getId(),
                "grant=" + row.getId() + " role=" + row.getRole() + " scope=" + row.getScopeType() + ":" + row.getScopeId()
                        + " expires=" + expires);
        refreshDisplayRole(target.getId());
        return views(List.of(row)).getFirst();
    }

    @Transactional
    public RoleGrantDtos.GrantView revoke(CurrentUser actor, long grantId, RoleGrantDtos.RevokeRequest request) {
        requireAdmin(actor);
        RoleGrant row = grantMapper.selectById(grantId);
        if (row == null) throw new NoSuchElementException("授权不存在");
        userMapper.lockById(row.getUserId());
        row = grantMapper.selectById(grantId);
        if (row.getRevokedAt() != null) throw new StateConflictException("这条授权已经撤销");
        LocalDateTime now = AppTime.nowLocal().truncatedTo(ChronoUnit.SECONDS);
        markRevoked(row, actor.id(), request.reason().trim(), now);
        audit.recordRequired(actor, "ROLE_REVOKE", "user", row.getUserId(),
                "grant=" + row.getId() + " role=" + row.getRole() + " reason=" + row.getRevokeReason());
        refreshDisplayRole(row.getUserId());
        return views(List.of(row)).getFirst();
    }

    @Transactional(readOnly = true)
    public List<RoleGrantDtos.GrantView> list(CurrentUser actor, String userUid, ScopeType scopeType, Long scopeId,
                                              boolean includeInactive) {
        requireAdmin(actor);
        LambdaQueryWrapper<RoleGrant> query = new LambdaQueryWrapper<RoleGrant>().orderByDesc(RoleGrant::getId).last("LIMIT 200");
        if (userUid != null && !userUid.isBlank()) {
            User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPublicUid, userUid.trim()));
            if (user == null) return List.of();
            query.eq(RoleGrant::getUserId, user.getId());
        }
        if (scopeType != null) query.eq(RoleGrant::getScopeType, scopeType.name());
        if (scopeId != null) query.eq(RoleGrant::getScopeId, scopeId);
        if (!includeInactive) {
            LocalDateTime now = AppTime.nowLocal();
            query.isNull(RoleGrant::getRevokedAt)
                    .and(w -> w.isNull(RoleGrant::getExpiresAt).or().gt(RoleGrant::getExpiresAt, now));
        }
        return views(grantMapper.selectList(query));
    }

    /** 当前用户能做什么、在哪些范围：客户端据此显示入口。 */
    @Transactional(readOnly = true)
    public RoleGrantDtos.CapabilitiesView capabilities(CurrentUser actor) {
        Grants grants = permissions.grantsOf(actor);
        List<RoleGrant> rows = grants.all().isEmpty() ? List.of()
                : grantMapper.selectBatchIds(grants.all().stream().map(Grant::id).toList());
        List<RoleGrantDtos.CapabilityScope> capabilities = new ArrayList<>();
        for (Capability capability : Capability.values()) {
            List<String> scopes = grants.platformAdmin() ? List.of("PLATFORM")
                    : grants.with(capability).stream()
                    .map(g -> g.scopeType() == ScopeType.PLATFORM ? "PLATFORM" : g.scopeType() + ":" + g.scopeId())
                    .distinct().toList();
            if (!scopes.isEmpty()) capabilities.add(new RoleGrantDtos.CapabilityScope(capability.code(), scopes));
        }
        return new RoleGrantDtos.CapabilitiesView(grants.platformAdmin() ? "ADMIN" : "USER", views(rows), capabilities);
    }

    // ---- 兼容与初始化 ----

    @Override
    @Transactional
    public void applyLegacyRole(CurrentUser actor, long userId, String role, String operatorScope) {
        requireAdmin(actor);
        User user = userMapper.lockById(userId);
        if (user == null) throw new NoSuchElementException("用户不存在");
        LocalDateTime now = AppTime.nowLocal().truncatedTo(ChronoUnit.SECONDS);
        if ("ADMIN".equals(role)) {
            setDisplay(userId, "ADMIN", "admin", "verified");
        } else {
            for (RoleGrant row : permissions.activeRows(userId, now)) {
                markRevoked(row, actor.id(), "旧接口改角色为 " + role, now);
            }
            if ("USER".equals(role)) {
                setDisplay(userId, "USER", "student", "none");
            } else {
                Role mapped = legacyRole(role, operatorScope);
                Long scopeId = switch (mapped.scopeType()) {
                    case SCHOOL -> user.getSchoolId();
                    case COLLEGE -> user.getCollegeId();
                    case MAJOR -> user.getMajorId();
                    case PLATFORM -> null;
                };
                if (scopeId == null) {
                    throw new IllegalArgumentException("此人资料里没有对应的学校、学院或专业，请改用 /api/admin/role-grants 指定范围");
                }
                insert(userId, mapped, resolveScope(mapped, scopeId), actor.id(), "旧接口授予", now, schoolYearEnd(now));
                // 先降成普通用户再重算，避免原来的 ADMIN 标记留在展示字段上。
                setDisplay(userId, "USER", "student", "none");
                refreshDisplayRole(userId);
            }
        }
        audit.recordRequired(actor, "USER_ROLE_UPDATE", "user", userId, "role=" + role + " scope=" + operatorScope);
    }

    @Override
    @Transactional
    public void ensureSeedGrant(long userId, Role role, Long scopeId) {
        LocalDateTime now = AppTime.nowLocal().truncatedTo(ChronoUnit.SECONDS);
        boolean exists = permissions.activeRows(userId, now).stream()
                .anyMatch(row -> row.getRole().equals(role.name()) && Objects.equals(row.getScopeId(), scopeId));
        if (!exists) insert(userId, role, resolveScope(role, scopeId), null, "演示数据", now, null);
        refreshDisplayRole(userId);
    }

    /** 授权到期不会触发任何写入，所以每小时把展示字段和有效授权对一次。 */
    @Scheduled(cron = "0 7 * * * *")
    public void refreshExpiredDisplayRoles() {
        userMapper.selectList(new LambdaQueryWrapper<User>().in(User::getRole, DISPLAY_ROLES).select(User::getId))
                .forEach(user -> refreshDisplayRole(user.getId()));
    }

    // ---- 内部 ----

    void refreshDisplayRole(long userId) {
        User user = userMapper.selectById(userId);
        if (user == null || "ADMIN".equals(user.getRole())) return;
        Grants grants = permissions.grantsOf(userId);
        Role display = grants.all().stream().map(Grant::role).filter(r -> r.displayRole() != null)
                .max(java.util.Comparator.comparingInt(Role::rank)).orElse(null);
        if (display == null) {
            // 没有范围的旧泛运营（OPERATOR）保持原样，等管理员确认；其余回到普通用户。
            if (DISPLAY_ROLES.contains(user.getRole())) setDisplay(userId, "USER", "student", "none");
            return;
        }
        if (!display.displayRole().equals(user.getRole()) || !display.displayScope().equals(user.getOperatorScope())) {
            setDisplay(userId, display.displayRole(), display.displayScope(), "verified");
        }
    }

    private void setDisplay(long userId, String role, String scope, String verified) {
        userMapper.update(null, new LambdaUpdateWrapper<User>().eq(User::getId, userId)
                .set(User::getRole, role).set(User::getOperatorScope, scope)
                .set(User::getVerifiedStatus, verified).set(User::getUpdatedAt, AppTime.nowLocal()));
    }

    private RoleGrant insert(long userId, Role role, Scope scope, Long grantedBy, String reason,
                             LocalDateTime now, LocalDateTime expires) {
        RoleGrant row = new RoleGrant();
        row.setUserId(userId);
        row.setRole(role.name());
        row.setScopeType(role.scopeType().name());
        row.setScopeId(scope.id());
        row.setSchoolId(scope.schoolId());
        row.setGrantedBy(grantedBy);
        row.setReason(reason);
        row.setStartsAt(now);
        row.setExpiresAt(expires);
        row.setCreatedAt(now);
        grantMapper.insert(row);
        return row;
    }

    private void markRevoked(RoleGrant row, Long actorId, String reason, LocalDateTime now) {
        grantMapper.update(null, new LambdaUpdateWrapper<RoleGrant>().eq(RoleGrant::getId, row.getId())
                .isNull(RoleGrant::getRevokedAt)
                .set(RoleGrant::getRevokedAt, now).set(RoleGrant::getRevokedBy, actorId)
                .set(RoleGrant::getRevokeReason, reason));
        row.setRevokedAt(now);
        row.setRevokedBy(actorId);
        row.setRevokeReason(reason);
    }

    private void requireAdmin(CurrentUser actor) {
        if (!permissions.grantsOf(actor).platformAdmin()) {
            throw new AccessDeniedException("只有平台管理员可以管理角色授权");
        }
    }

    record Scope(Long id, Long schoolId, String name) {}

    /** 范围必须真实存在；所属学校从字典表查，不信请求参数。 */
    Scope resolveScope(Role role, Long scopeId) {
        return switch (role.scopeType()) {
            case PLATFORM -> {
                if (scopeId != null) throw new IllegalArgumentException(role.label() + "是平台级角色，不需要范围");
                yield new Scope(null, null, "全平台");
            }
            case SCHOOL -> {
                School school = scopeId == null ? null : schoolMapper.selectById(scopeId);
                if (school == null || !"active".equals(school.getStatus())) throw new IllegalArgumentException("学校不存在");
                yield new Scope(school.getId(), school.getId(), school.getName());
            }
            case COLLEGE -> {
                College college = scopeId == null ? null : collegeMapper.selectById(scopeId);
                if (college == null) throw new IllegalArgumentException("学院不存在");
                yield new Scope(college.getId(), college.getSchoolId(), college.getName());
            }
            case MAJOR -> {
                Major major = scopeId == null ? null : majorMapper.selectById(scopeId);
                if (major == null) throw new IllegalArgumentException("专业不存在");
                yield new Scope(major.getId(), major.getSchoolId(), major.getName());
            }
        };
    }

    /** 与旧 ActivityRules.levelOf 一致：以 operatorScope 为准，没给时按角色名推断。 */
    static Role legacyRole(String role, String operatorScope) {
        String scope = operatorScope == null || operatorScope.isBlank() ? switch (role) {
            case "SCHOOL_OPERATOR" -> "school";
            case "COLLEGE_OPERATOR" -> "college";
            case "MAJOR_OPERATOR" -> "major";
            default -> "";
        } : operatorScope;
        return switch (scope) {
            case "school" -> Role.SCHOOL_OPERATOR;
            case "college" -> Role.COLLEGE_OPERATOR;
            case "major" -> Role.MAJOR_OPERATOR;
            default -> throw new IllegalArgumentException("运营角色需要 school / college / major 范围，请改用 /api/admin/role-grants");
        };
    }

    /** 默认到当前学年结束：8 月 31 日 23:59:59。9 月及以后授予的到明年 8 月底。 */
    static LocalDateTime schoolYearEnd(LocalDateTime now) {
        int year = now.getMonthValue() >= 9 ? now.getYear() + 1 : now.getYear();
        return LocalDate.of(year, 8, 31).atTime(LocalTime.of(23, 59, 59));
    }

    private List<RoleGrantDtos.GrantView> views(Collection<RoleGrant> rows) {
        if (rows.isEmpty()) return List.of();
        Set<Long> userIds = new java.util.HashSet<>();
        rows.forEach(row -> {
            userIds.add(row.getUserId());
            if (row.getGrantedBy() != null) userIds.add(row.getGrantedBy());
        });
        Map<Long, User> users = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        Map<String, String> names = scopeNames(rows);
        LocalDateTime now = AppTime.nowLocal();
        return rows.stream().map(row -> {
            User user = users.get(row.getUserId());
            User grantedBy = row.getGrantedBy() == null ? null : users.get(row.getGrantedBy());
            Role role = roleOrNull(row.getRole());
            boolean active = row.getRevokedAt() == null && !row.getStartsAt().isAfter(now)
                    && (row.getExpiresAt() == null || row.getExpiresAt().isAfter(now));
            return new RoleGrantDtos.GrantView(row.getId(), user == null ? null : user.getPublicUid(),
                    user == null ? null : user.getNickname(), row.getRole(), role == null ? row.getRole() : role.label(),
                    row.getScopeType(), row.getScopeId(), names.get(row.getScopeType() + ":" + row.getScopeId()),
                    row.getReason(), grantedBy == null ? null : grantedBy.getPublicUid(),
                    AppTime.toOffset(row.getStartsAt()), AppTime.toOffset(row.getExpiresAt()),
                    AppTime.toOffset(row.getRevokedAt()), row.getRevokeReason(), active);
        }).toList();
    }

    private Map<String, String> scopeNames(Collection<RoleGrant> rows) {
        Map<String, String> names = new HashMap<>();
        names.put("PLATFORM:null", "全平台");
        List<Long> schools = ids(rows, ScopeType.SCHOOL);
        List<Long> colleges = ids(rows, ScopeType.COLLEGE);
        List<Long> majors = ids(rows, ScopeType.MAJOR);
        if (!schools.isEmpty()) schoolMapper.selectBatchIds(schools).forEach(s -> names.put("SCHOOL:" + s.getId(), s.getName()));
        if (!colleges.isEmpty()) collegeMapper.selectBatchIds(colleges).forEach(c -> names.put("COLLEGE:" + c.getId(), c.getName()));
        if (!majors.isEmpty()) majorMapper.selectBatchIds(majors).forEach(m -> names.put("MAJOR:" + m.getId(), m.getName()));
        return names;
    }

    private static List<Long> ids(Collection<RoleGrant> rows, ScopeType type) {
        return rows.stream().filter(r -> type.name().equals(r.getScopeType()) && r.getScopeId() != null)
                .map(RoleGrant::getScopeId).distinct().toList();
    }

    private static Role roleOrNull(String name) {
        try {
            return Role.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
