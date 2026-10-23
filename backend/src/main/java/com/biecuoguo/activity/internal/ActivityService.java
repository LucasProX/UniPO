package com.biecuoguo.activity.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.biecuoguo.achievement.api.AttendanceBadges;
import com.biecuoguo.activity.api.ActivityDtos;
import com.biecuoguo.admin.api.AdminAuditApi;
import com.biecuoguo.notification.api.NotificationApi;
import com.biecuoguo.config.AppProperties;
import com.biecuoguo.config.AppTime;
import com.biecuoguo.domain.College;
import com.biecuoguo.domain.Major;
import com.biecuoguo.domain.User;
import com.biecuoguo.error.StateConflictException;
import com.biecuoguo.mapper.CollegeMapper;
import com.biecuoguo.mapper.MajorMapper;
import com.biecuoguo.mapper.UserMapper;
import com.biecuoguo.security.CurrentUser;
import com.biecuoguo.service.ExperienceService;
import com.biecuoguo.wallet.api.WalletApi;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ActivityService {
    private static final Set<String> TABS = Set.of("recommend", "week");
    private static final int ATTENDEE_PREVIEW = 8;
    static final Set<String> ACTIVE = Set.of("REGISTERED", "WAITLISTED", "PENDING");
    /** 组织者撤销签到的期限：签到后 24 小时内。 */
    static final java.time.Duration REVOKE_WINDOW = java.time.Duration.ofHours(24);

    private final ActivityMapper activityMapper;
    private final ActivityRegistrationMapper registrationMapper;
    private final UserMapper userMapper;
    private final CollegeMapper collegeMapper;
    private final MajorMapper majorMapper;
    private final AppProperties properties;
    private final ExperienceService experienceService;
    private final WalletApi walletApi;
    private final NotificationApi notifications;
    private final AdminAuditApi audit;

    public ActivityService(ActivityMapper activityMapper, ActivityRegistrationMapper registrationMapper,
                           UserMapper userMapper, CollegeMapper collegeMapper, MajorMapper majorMapper,
                           AppProperties properties, ExperienceService experienceService, WalletApi walletApi,
                           NotificationApi notifications, AdminAuditApi audit) {
        this.notifications = notifications;
        this.audit = audit;
        this.experienceService = experienceService;
        this.walletApi = walletApi;
        this.activityMapper = activityMapper;
        this.registrationMapper = registrationMapper;
        this.userMapper = userMapper;
        this.collegeMapper = collegeMapper;
        this.majorMapper = majorMapper;
        this.properties = properties;
    }

    // ---- 发布 ----

    public ActivityDtos.OrganizerProfile organizerProfile(CurrentUser actor) {
        User user = activeUser(actor.id());
        ActivityRules.Level level = ActivityRules.levelOf(user.getRole(), user.getOperatorScope());
        if (level == null) {
            return new ActivityDtos.OrganizerProfile(false, null, List.of(), user.getCollegeId(), user.getCollege(),
                    user.getMajorId(), user.getMajor(), 0, 0);
        }
        return new ActivityDtos.OrganizerProfile(true, level.name(), level.allowedScopes(), user.getCollegeId(),
                user.getCollege(), user.getMajorId(), user.getMajor(), level.maxXp, level.maxCoins);
    }

    @Transactional
    public ActivityDtos.ActivityView create(ActivityDtos.CreateActivityRequest request, CurrentUser actor) {
        User organizer = activeUser(actor.id());
        ActivityRules.Level level = ActivityRules.levelOf(organizer.getRole(), organizer.getOperatorScope());
        if (level == null) {
            throw new AccessDeniedException("只有认证的校园运营可以发布官方活动");
        }
        Long schoolId = schoolOf(organizer);
        String scope = upper(request.audienceScope());
        String category = upper(request.category());
        if (!ActivityRules.SCOPES.contains(scope)) throw new IllegalArgumentException("面向范围不合法");
        if (!ActivityRules.CATEGORIES.contains(category)) throw new IllegalArgumentException("活动类型不合法");

        Long collegeId = "SCHOOL".equals(scope) ? null : request.audienceCollegeId();
        Long majorId = "MAJOR".equals(scope) ? request.audienceMajorId() : null;
        Major major = majorId == null ? null : majorMapper.selectById(majorId);
        if (majorId != null && (major == null || !schoolId.equals(major.getSchoolId()))) {
            throw new IllegalArgumentException("专业不属于本校");
        }
        if ("MAJOR".equals(scope) && major != null) collegeId = major.getCollegeId();
        if (collegeId != null) {
            College college = collegeMapper.selectById(collegeId);
            if (college == null || !schoolId.equals(college.getSchoolId())) {
                throw new IllegalArgumentException("学院不属于本校");
            }
        }
        ActivityRules.checkAudience(level, organizer.getCollegeId(), organizer.getMajorId(), scope,
                collegeId, majorId, major == null ? null : major.getCollegeId());

        int xp = request.rewardXp() == null ? 0 : request.rewardXp();
        int coins = request.rewardCoins() == null ? 0 : request.rewardCoins();
        ActivityRules.checkReward(level, xp, coins);

        LocalDateTime now = AppTime.nowLocal();
        LocalDateTime startsAt = AppTime.toLocal(request.startsAt()).withNano(0);
        LocalDateTime endsAt = AppTime.toLocal(request.endsAt()).withNano(0);
        LocalDateTime deadline = request.signupDeadline() == null ? startsAt
                : AppTime.toLocal(request.signupDeadline()).withNano(0);
        if (!endsAt.isAfter(startsAt)) throw new IllegalArgumentException("结束时间必须晚于开始时间");
        if (!startsAt.isAfter(now)) throw new IllegalArgumentException("开始时间必须晚于现在");
        if (deadline.isAfter(startsAt) || !deadline.isAfter(now)) {
            throw new IllegalArgumentException("报名截止时间必须在现在之后、活动开始之前");
        }
        if (request.capacity() != null && (request.capacity() < 1 || request.capacity() > 10_000)) {
            throw new IllegalArgumentException("名额需要在 1 到 10000 之间，不限名额请留空");
        }
        if (request.audienceEntryYear() != null
                && (request.audienceEntryYear() < now.getYear() - 10 || request.audienceEntryYear() > now.getYear() + 1)) {
            throw new IllegalArgumentException("面向的入学年份不正确");
        }

        Activity activity = new Activity();
        activity.setSchoolId(schoolId);
        activity.setOrganizerId(organizer.getId());
        activity.setOrganizerName(blankToDefault(request.organizerName(), organizer.getNickname()));
        activity.setTitle(request.title().trim());
        activity.setCategory(category);
        activity.setCoverUrl(blankToNull(request.coverUrl()));
        activity.setDescription(request.description().trim());
        activity.setNotice(blankToNull(request.notice()));
        activity.setLocation(request.location().trim());
        activity.setStartsAt(startsAt);
        activity.setEndsAt(endsAt);
        activity.setSignupDeadline(deadline);
        activity.setAudienceScope(scope);
        activity.setAudienceCollegeId(collegeId);
        activity.setAudienceMajorId(majorId);
        activity.setAudienceEntryYear(request.audienceEntryYear());
        activity.setCapacity(request.capacity());
        activity.setWaitlistEnabled(request.waitlistEnabled() == null || request.waitlistEnabled());
        activity.setRequireApproval(Boolean.TRUE.equals(request.requireApproval()));
        activity.setRewardXp(xp);
        activity.setRewardCoins(coins);
        activity.setStatus("PUBLISHED");
        activity.setRegisteredCount(0);
        activity.setWaitlistCount(0);
        activity.setCheckinSecret(CheckinTokens.newSecret());
        activity.setCheckinCode(CheckinTokens.newCode());
        activity.setCreatedAt(now);
        activity.setUpdatedAt(now);
        activityMapper.insert(activity);
        return view(activity, organizer);
    }

    // ---- 浏览 ----

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public ActivityDtos.ActivityPage page(String requestedTab, String cursor, Integer requestedLimit, CurrentUser viewer) {
        String tab = requestedTab == null || requestedTab.isBlank() ? "recommend" : requestedTab.trim().toLowerCase(Locale.ROOT);
        if (!TABS.contains(tab)) throw new IllegalArgumentException("活动列表类型不合法");
        int limit = limit(requestedLimit);
        User user = viewer == null ? null : userMapper.selectById(viewer.id());
        long schoolId = user == null ? properties.defaultSchoolId() : schoolOf(user);
        LocalDateTime now = AppTime.nowLocal();
        Position after = decode(cursor, tab);
        List<Activity> rows = activityMapper.selectUpcoming(schoolId, now,
                "week".equals(tab) ? now.plusDays(7) : null,
                after == null ? null : after.startsAt(), after == null ? null : after.id(), limit + 1);
        return page(rows, limit, tab, user);
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public ActivityDtos.ActivityPage mine(String state, String cursor, Integer requestedLimit, CurrentUser viewer) {
        boolean history = "history".equalsIgnoreCase(state);
        String tab = history ? "history" : "upcoming";
        int limit = limit(requestedLimit);
        User user = activeUser(viewer.id());
        Position after = decode(cursor, tab);
        List<Long> ids = registrationMapper.selectMineIds(user.getId(), history, AppTime.nowLocal(),
                after == null ? null : after.startsAt(), after == null ? null : after.id(), limit + 1);
        if (ids.isEmpty()) return new ActivityDtos.ActivityPage(List.of(), null, false, limit);
        Map<Long, Activity> byId = activityMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Activity::getId, Function.identity()));
        List<Activity> rows = ids.stream().map(byId::get).filter(Objects::nonNull).toList();
        return page(rows, limit, tab, user);
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public ActivityDtos.ActivityView detail(long id, CurrentUser viewer) {
        User user = viewer == null ? null : userMapper.selectById(viewer.id());
        long schoolId = user == null ? properties.defaultSchoolId() : schoolOf(user);
        Activity activity = activityMapper.selectById(id);
        if (activity == null || activity.getSchoolId() != schoolId) {
            throw new NoSuchElementException("活动不存在");
        }
        return view(activity, user);
    }

    // ---- 报名 ----

    @Transactional
    public ActivityDtos.ActivityView register(long id, CurrentUser viewer) {
        User user = activeUser(viewer.id());
        Activity activity = lockVisible(id, user);
        ActivityRegistration existing = registrationMapper.lockOne(id, user.getId());
        if (existing != null && ACTIVE.contains(existing.getStatus())) {
            return view(activity, user);
        }
        if (existing != null && "REJECTED".equals(existing.getStatus())) {
            throw new StateConflictException("组织者未通过你的报名");
        }
        LocalDateTime now = AppTime.nowLocal();
        String phase = ActivityRules.phase(activity.getStatus(), activity.getSignupDeadline(), activity.getStartsAt(),
                activity.getEndsAt(), now);
        if (!"OPEN".equals(phase)) throw new StateConflictException("活动当前不能报名");
        String reason = ActivityRules.ineligibleReason(activity, schoolOf(user), user.getCollegeId(), user.getMajorId(),
                user.getEntryYear(), audienceLabel(activity));
        // 不在报名范围是业务状态而不是越权，返回具体原因给用户看。
        if (reason != null) throw new StateConflictException(reason);

        boolean needsReview = Boolean.TRUE.equals(activity.getRequireApproval());
        boolean hasSeat = activity.getCapacity() == null || activity.getRegisteredCount() < activity.getCapacity();
        if (!needsReview && !hasSeat && !Boolean.TRUE.equals(activity.getWaitlistEnabled())) {
            throw new StateConflictException("名额已满");
        }
        // 需要审核的活动先进待审核，不占名额；通过时再按名额决定已报名或候补。
        String status = needsReview ? "PENDING" : hasSeat ? "REGISTERED" : "WAITLISTED";
        if (existing == null) {
            ActivityRegistration registration = new ActivityRegistration();
            registration.setActivityId(id);
            registration.setUserId(user.getId());
            registration.setStatus(status);
            registration.setQueuedAt(AppTime.nowLocal());
            registration.setCreatedAt(now);
            registration.setUpdatedAt(now);
            registrationMapper.insert(registration);
        } else {
            registrationMapper.update(null, new LambdaUpdateWrapper<ActivityRegistration>()
                    .eq(ActivityRegistration::getId, existing.getId())
                    .set(ActivityRegistration::getStatus, status)
                    .set(ActivityRegistration::getQueuedAt, AppTime.nowLocal())
                    .set(ActivityRegistration::getCancelledAt, null)
                    .set(ActivityRegistration::getUpdatedAt, now));
        }
        if (!needsReview) adjustCounts(activity, hasSeat ? 1 : 0, hasSeat ? 0 : 1, now);
        return view(activity, user);
    }

    /** 取消已报名会按报名先后递补一位候补；活动开始后不能再取消。 */
    @Transactional
    public ActivityDtos.ActivityView cancel(long id, CurrentUser viewer) {
        User user = activeUser(viewer.id());
        Activity activity = lockVisible(id, user);
        ActivityRegistration existing = registrationMapper.lockOne(id, user.getId());
        if (existing == null || "CANCELLED".equals(existing.getStatus())) {
            return view(activity, user);
        }
        LocalDateTime now = AppTime.nowLocal();
        if (!now.isBefore(activity.getStartsAt()) && !"CANCELLED".equals(activity.getStatus())) {
            throw new StateConflictException("活动已开始，不能取消报名");
        }
        if ("REJECTED".equals(existing.getStatus())) return view(activity, user);
        if (existing.getCheckedInAt() != null) throw new StateConflictException("已签到，不能取消报名");
        String previous = existing.getStatus();
        registrationMapper.update(null, new LambdaUpdateWrapper<ActivityRegistration>()
                .eq(ActivityRegistration::getId, existing.getId())
                .set(ActivityRegistration::getStatus, "CANCELLED")
                .set(ActivityRegistration::getCancelledAt, now)
                .set(ActivityRegistration::getUpdatedAt, now));
        int registeredDelta = "REGISTERED".equals(previous) ? -1 : 0;
        int waitlistDelta = "WAITLISTED".equals(previous) ? -1 : 0;
        adjustCounts(activity, registeredDelta, waitlistDelta, now);
        if ("REGISTERED".equals(previous) && "PUBLISHED".equals(activity.getStatus())) promoteWaitlist(activity, 1, now);
        return view(activity, user);
    }

    // ---- 签到 ----

    /** 组织者签到页：发布者本人或管理员可见；二维码内容每 30 秒轮换。 */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public ActivityDtos.OrganizerCheckinView organizerCheckin(long id, CurrentUser actor) {
        User user = activeUser(actor.id());
        Activity activity = activityMapper.selectById(id);
        if (activity == null || !activity.getSchoolId().equals(schoolOf(user))) throw new NoSuchElementException("活动不存在");
        if (!canManage(activity, user)) throw new AccessDeniedException("只有活动发布者可以查看签到码");
        Instant instant = Instant.now();
        LocalDateTime now = AppTime.nowLocal();
        String token = CheckinTokens.token(activity.getCheckinSecret(), id, CheckinTokens.slot(instant));
        long checkedIn = registrationMapper.selectCount(new LambdaQueryWrapper<ActivityRegistration>()
                .eq(ActivityRegistration::getActivityId, id).isNotNull(ActivityRegistration::getCheckedInAt));
        return new ActivityDtos.OrganizerCheckinView("unipo://checkin?activity=" + id + "&token=" + token,
                activity.getCheckinCode(), CheckinTokens.secondsLeftInSlot(instant),
                "PUBLISHED".equals(activity.getStatus())
                        && CheckinTokens.windowOpen(activity.getStartsAt(), activity.getEndsAt(), now),
                AppTime.toOffset(activity.getStartsAt().minus(CheckinTokens.OPENS_BEFORE_START)),
                AppTime.toOffset(activity.getEndsAt()), (int) checkedIn, activity.getRegisteredCount());
    }

    /**
     * 扫码或签到码签到，成功即发经验与金币。锁住本人报名行后判断是否已签到，重复请求返回原结果、不重复发奖；
     * 金币按「activity-checkin:报名 ID」幂等入账。锁顺序：报名行 → 用户行（经验）→ 钱包行。
     */
    @Transactional
    public ActivityDtos.CheckinResult checkin(long id, ActivityDtos.CheckinRequest request, CurrentUser actor) {
        User user = activeUser(actor.id());
        Activity activity = activityMapper.selectById(id);
        if (activity == null || !activity.getSchoolId().equals(schoolOf(user))) throw new NoSuchElementException("活动不存在");
        ActivityRegistration registration = registrationMapper.lockOne(id, user.getId());
        if (registration != null && registration.getCheckedInAt() != null) {
            return new ActivityDtos.CheckinResult(view(activity, user), true, registration.getRewardXpGranted(),
                    registration.getRewardCoinsGranted());
        }
        if (!"PUBLISHED".equals(activity.getStatus())) throw new StateConflictException("活动已取消");
        LocalDateTime now = AppTime.nowLocal();
        if (!CheckinTokens.windowOpen(activity.getStartsAt(), activity.getEndsAt(), now)) {
            throw new StateConflictException(now.isBefore(activity.getStartsAt()) ? "活动开始前 30 分钟开放签到" : "签到已结束");
        }
        if (registration == null || "CANCELLED".equals(registration.getStatus())) throw new StateConflictException("你还没有报名这个活动");
        if ("WAITLISTED".equals(registration.getStatus())) throw new StateConflictException("候补中的同学不能签到");
        if (!"REGISTERED".equals(registration.getStatus())) throw new StateConflictException("报名尚未通过，不能签到");
        if (registration.getCheckinRevokedAt() != null) throw new StateConflictException("你的签到已被组织者撤销");
        String method;
        if (request.token() != null && !request.token().isBlank()) {
            if (!CheckinTokens.validToken(activity.getCheckinSecret(), id, request.token().trim(), Instant.now())) {
                throw new IllegalArgumentException("二维码已过期，请重新扫描现场的签到码");
            }
            method = "QR";
        } else if (CheckinTokens.validCode(activity.getCheckinCode(), request.code())) {
            method = "CODE";
        } else {
            throw new IllegalArgumentException("签到码不正确");
        }
        int[] granted = grantCheckin(activity, registration, method, now);
        return new ActivityDtos.CheckinResult(view(activity, user), false, granted[0], granted[1]);
    }

    /** 记签到并发奖；调用方已锁住报名行并确认尚未签到。 */
    private int[] grantCheckin(Activity activity, ActivityRegistration registration, String method, LocalDateTime now) {
        int xp = activity.getRewardXp() == null ? 0 : activity.getRewardXp();
        if (xp > 0) experienceService.award(registration.getUserId(), xp);
        int coins = walletApi.credit(registration.getUserId(), activity.getRewardCoins() == null ? 0 : activity.getRewardCoins(),
                "ACTIVITY_CHECKIN", activity.getId(), "activity-checkin:" + registration.getId(),
                "参加活动：" + activity.getTitle());
        registrationMapper.update(null, new LambdaUpdateWrapper<ActivityRegistration>()
                .eq(ActivityRegistration::getId, registration.getId())
                .set(ActivityRegistration::getCheckedInAt, now)
                .set(ActivityRegistration::getCheckinMethod, method)
                .set(ActivityRegistration::getRewardXpGranted, xp)
                .set(ActivityRegistration::getRewardCoinsGranted, coins)
                .set(ActivityRegistration::getUpdatedAt, now));
        // 活动徽章由签到场次实时推导；这里只在恰好跨过门槛时提醒一次（撤销后再达到不重复提醒）。
        int attended = registrationMapper.countAttended(registration.getUserId());
        AttendanceBadges.reachedAt(attended).ifPresent(code -> notifications.notify(List.of(registration.getUserId()),
                "BADGE_EARNED", "解锁活动徽章",
                "累计参加 " + attended + " 场校园活动，解锁了新徽章，去成长档案看看吧。", activity.getId(), "badge:" + code));
        return new int[]{xp, coins};
    }

    // ---- 组织者 ----

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public ActivityDtos.ActivityPage organized(String cursor, Integer requestedLimit, CurrentUser actor) {
        User user = activeUser(actor.id());
        int limit = limit(requestedLimit);
        Long before = cursor == null || cursor.isBlank() ? null : parseId(cursor, "活动游标不合法");
        List<Activity> rows = activityMapper.selectList(new LambdaQueryWrapper<Activity>()
                .eq(Activity::getOrganizerId, user.getId())
                .lt(before != null, Activity::getId, before)
                .orderByDesc(Activity::getId)
                .last("LIMIT " + (limit + 1)));
        boolean more = rows.size() > limit;
        List<Activity> page = more ? rows.subList(0, limit) : rows;
        return new ActivityDtos.ActivityPage(views(page, user), more ? String.valueOf(page.getLast().getId()) : null, more, limit);
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public ActivityDtos.RosterPage roster(long id, String filter, String cursor, Integer requestedLimit, CurrentUser actor) {
        User user = activeUser(actor.id());
        Activity activity = managed(id, user, false);
        int limit = Math.min(limit(requestedLimit), 50);
        Long after = cursor == null || cursor.isBlank() ? null : parseId(cursor, "名单游标不合法");
        String key = filter == null || filter.isBlank() ? "all" : filter.trim().toLowerCase(Locale.ROOT);
        LambdaQueryWrapper<ActivityRegistration> query = new LambdaQueryWrapper<ActivityRegistration>()
                .eq(ActivityRegistration::getActivityId, id)
                .gt(after != null, ActivityRegistration::getId, after);
        switch (key) {
            case "registered" -> query.eq(ActivityRegistration::getStatus, "REGISTERED");
            case "pending" -> query.eq(ActivityRegistration::getStatus, "PENDING");
            case "waitlisted" -> query.eq(ActivityRegistration::getStatus, "WAITLISTED");
            case "checked_in" -> query.isNotNull(ActivityRegistration::getCheckedInAt);
            case "all" -> query.in(ActivityRegistration::getStatus, ACTIVE);
            default -> throw new IllegalArgumentException("名单筛选不合法");
        }
        List<ActivityRegistration> rows = registrationMapper.selectList(query.orderByAsc(ActivityRegistration::getId)
                .last("LIMIT " + (limit + 1)));
        boolean more = rows.size() > limit;
        List<ActivityRegistration> page = more ? rows.subList(0, limit) : rows;
        Map<Long, User> users = page.isEmpty() ? Map.of() : userMapper.selectBatchIds(
                page.stream().map(ActivityRegistration::getUserId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        LocalDateTime now = AppTime.nowLocal();
        List<ActivityDtos.RosterEntry> entries = page.stream().map(row -> {
            User attendee = users.get(row.getUserId());
            boolean revocable = row.getCheckedInAt() != null && now.isBefore(row.getCheckedInAt().plus(REVOKE_WINDOW));
            return new ActivityDtos.RosterEntry(row.getId(), attendee == null ? "" : attendee.getPublicUid(),
                    attendee == null ? "已注销用户" : attendee.getNickname(), attendee == null ? null : attendee.getAvatarUrl(),
                    attendee == null ? null : attendee.getCollege(), attendee == null ? null : attendee.getMajor(),
                    attendee == null ? null : attendee.getGrade(), row.getStatus(),
                    AppTime.toOffset(row.getQueuedAt()), AppTime.toOffset(row.getCheckedInAt()), row.getCheckinMethod(),
                    nz(row.getRewardXpGranted()), nz(row.getRewardCoinsGranted()), AppTime.toOffset(row.getCheckinRevokedAt()),
                    row.getRevokeReason(), revocable);
        }).toList();
        int pending = Math.toIntExact(registrationMapper.selectCount(new LambdaQueryWrapper<ActivityRegistration>()
                .eq(ActivityRegistration::getActivityId, id).eq(ActivityRegistration::getStatus, "PENDING")));
        int checkedIn = Math.toIntExact(registrationMapper.selectCount(new LambdaQueryWrapper<ActivityRegistration>()
                .eq(ActivityRegistration::getActivityId, id).isNotNull(ActivityRegistration::getCheckedInAt)));
        return new ActivityDtos.RosterPage(entries, more ? String.valueOf(page.getLast().getId()) : null, more,
                activity.getRegisteredCount(), activity.getWaitlistCount(), pending, checkedIn);
    }

    /** 通过待审核报名：有名额进已报名，否则（允许候补时）进候补。 */
    @Transactional
    public ActivityDtos.RosterEntry approve(long id, long registrationId, CurrentUser actor) {
        User user = activeUser(actor.id());
        Activity activity = managed(id, user, true);
        ActivityRegistration registration = ownRegistration(id, registrationId);
        if (!"PENDING".equals(registration.getStatus())) throw new StateConflictException("这条报名已处理");
        if (!"PUBLISHED".equals(activity.getStatus())) throw new StateConflictException("活动已取消");
        LocalDateTime now = AppTime.nowLocal();
        boolean hasSeat = activity.getCapacity() == null || activity.getRegisteredCount() < activity.getCapacity();
        if (!hasSeat && !Boolean.TRUE.equals(activity.getWaitlistEnabled())) throw new StateConflictException("名额已满");
        String status = hasSeat ? "REGISTERED" : "WAITLISTED";
        registrationMapper.update(null, new LambdaUpdateWrapper<ActivityRegistration>()
                .eq(ActivityRegistration::getId, registrationId)
                .set(ActivityRegistration::getStatus, status)
                .set(ActivityRegistration::getQueuedAt, AppTime.nowLocal())
                .set(ActivityRegistration::getReviewedBy, user.getId())
                .set(ActivityRegistration::getReviewedAt, now)
                .set(ActivityRegistration::getUpdatedAt, now));
        adjustCounts(activity, hasSeat ? 1 : 0, hasSeat ? 0 : 1, now);
        notifications.notify(List.of(registration.getUserId()), "REGISTRATION_APPROVED",
                hasSeat ? "报名已通过" : "报名已通过，正在候补", "「" + activity.getTitle() + "」"
                        + (hasSeat ? "报名成功，记得按时参加。" : "名额已满，有空位时按顺序递补。"),
                id, "approved:" + registrationId);
        audit.record(actor, "ACTIVITY_REGISTRATION_APPROVE", "activity_registration", registrationId, "activity=" + id);
        return rosterEntry(registrationId);
    }

    @Transactional
    public ActivityDtos.RosterEntry reject(long id, long registrationId, CurrentUser actor) {
        User user = activeUser(actor.id());
        Activity activity = managed(id, user, true);
        ActivityRegistration registration = ownRegistration(id, registrationId);
        if (!"PENDING".equals(registration.getStatus())) throw new StateConflictException("这条报名已处理");
        LocalDateTime now = AppTime.nowLocal();
        registrationMapper.update(null, new LambdaUpdateWrapper<ActivityRegistration>()
                .eq(ActivityRegistration::getId, registrationId)
                .set(ActivityRegistration::getStatus, "REJECTED")
                .set(ActivityRegistration::getReviewedBy, user.getId())
                .set(ActivityRegistration::getReviewedAt, now)
                .set(ActivityRegistration::getUpdatedAt, now));
        notifications.notify(List.of(registration.getUserId()), "REGISTRATION_REJECTED", "报名未通过",
                "「" + activity.getTitle() + "」的报名未通过，可以看看其他活动。", id, "rejected:" + registrationId);
        audit.record(actor, "ACTIVITY_REGISTRATION_REJECT", "activity_registration", registrationId, "activity=" + id);
        return rosterEntry(registrationId);
    }

    /** 手动补签：签到窗开放后到活动结束后 24 小时内，与自助签到发同样的奖励。 */
    @Transactional
    public ActivityDtos.RosterEntry manualCheckin(long id, long registrationId, CurrentUser actor) {
        User user = activeUser(actor.id());
        Activity activity = managed(id, user, false);
        ActivityRegistration registration = registrationMapper.lockOne(id, ownRegistration(id, registrationId).getUserId());
        if (registration.getCheckedInAt() != null) return rosterEntry(registrationId);
        if (!"REGISTERED".equals(registration.getStatus())) throw new StateConflictException("只有已报名的同学可以补签");
        if (registration.getCheckinRevokedAt() != null) throw new StateConflictException("签到已被撤销，不能再补签");
        LocalDateTime now = AppTime.nowLocal();
        if (now.isBefore(activity.getStartsAt().minus(CheckinTokens.OPENS_BEFORE_START))
                || !now.isBefore(activity.getEndsAt().plus(REVOKE_WINDOW))) {
            throw new StateConflictException("只能在签到开放后到活动结束后 24 小时内补签");
        }
        grantCheckin(activity, registration, "MANUAL", now);
        audit.recordRequired(actor, "ACTIVITY_MANUAL_CHECKIN", "activity_registration", registrationId, "activity=" + id);
        return rosterEntry(registrationId);
    }

    /** 撤销作弊签到：签到后 24 小时内，按实际发放的数额扣回经验与金币，撤销后不能再签到。 */
    @Transactional
    public ActivityDtos.RosterEntry revokeCheckin(long id, long registrationId, ActivityDtos.ReasonRequest request,
                                                  CurrentUser actor) {
        User user = activeUser(actor.id());
        Activity activity = managed(id, user, false);
        ActivityRegistration registration = registrationMapper.lockOne(id, ownRegistration(id, registrationId).getUserId());
        if (registration.getCheckedInAt() == null) throw new StateConflictException("这位同学还没有签到");
        LocalDateTime now = AppTime.nowLocal();
        if (!now.isBefore(registration.getCheckedInAt().plus(REVOKE_WINDOW))) {
            throw new StateConflictException("签到超过 24 小时，不能再撤销");
        }
        String reason = request.reason().trim();
        int xp = nz(registration.getRewardXpGranted());
        if (xp > 0) experienceService.revoke(registration.getUserId(), xp);
        int coins = walletApi.debit(registration.getUserId(), nz(registration.getRewardCoinsGranted()),
                "ACTIVITY_CHECKIN_REVOKE", activity.getId(), "activity-checkin-revoke:" + registrationId,
                "撤销签到：" + activity.getTitle());
        registrationMapper.update(null, new LambdaUpdateWrapper<ActivityRegistration>()
                .eq(ActivityRegistration::getId, registrationId)
                .set(ActivityRegistration::getCheckedInAt, null)
                .set(ActivityRegistration::getCheckinRevokedAt, now)
                .set(ActivityRegistration::getRevokeReason, reason)
                .set(ActivityRegistration::getRewardXpGranted, 0)
                .set(ActivityRegistration::getRewardCoinsGranted, 0)
                .set(ActivityRegistration::getUpdatedAt, now));
        notifications.notify(List.of(registration.getUserId()), "CHECKIN_REVOKED", "签到已被撤销",
                "「" + activity.getTitle() + "」的签到被组织者撤销，原因：" + reason + "。已扣回 " + xp + " 经验、" + coins + " 金币。",
                id, "revoked:" + registrationId);
        audit.recordRequired(actor, "ACTIVITY_CHECKIN_REVOKE", "activity_registration", registrationId,
                "activity=" + id + " xp=" + xp + " coins=" + coins + " reason=" + reason);
        return rosterEntry(registrationId);
    }

    /** 编辑活动：开始前可改；时间、地点变化通知所有报名者；扩容时按顺序递补候补。 */
    @Transactional
    public ActivityDtos.ActivityView update(long id, ActivityDtos.UpdateActivityRequest request, CurrentUser actor) {
        User user = activeUser(actor.id());
        Activity activity = managed(id, user, true);
        LocalDateTime now = AppTime.nowLocal();
        if (!"PUBLISHED".equals(activity.getStatus())) throw new StateConflictException("活动已取消，不能编辑");
        if (!now.isBefore(activity.getStartsAt())) throw new StateConflictException("活动已开始，不能编辑");
        LocalDateTime startsAt = request.startsAt() == null ? activity.getStartsAt() : AppTime.toLocal(request.startsAt()).withNano(0);
        LocalDateTime endsAt = request.endsAt() == null ? activity.getEndsAt() : AppTime.toLocal(request.endsAt()).withNano(0);
        LocalDateTime deadline = request.signupDeadline() == null ? activity.getSignupDeadline()
                : AppTime.toLocal(request.signupDeadline()).withNano(0);
        if (!endsAt.isAfter(startsAt)) throw new IllegalArgumentException("结束时间必须晚于开始时间");
        if (!startsAt.isAfter(now)) throw new IllegalArgumentException("开始时间必须晚于现在");
        if (deadline.isAfter(startsAt)) throw new IllegalArgumentException("报名截止时间必须在活动开始之前");
        Integer capacity = Boolean.TRUE.equals(request.clearCapacity()) ? null
                : request.capacity() != null ? request.capacity() : activity.getCapacity();
        if (capacity != null && (capacity < 1 || capacity > 10_000)) throw new IllegalArgumentException("名额需要在 1 到 10000 之间");
        if (capacity != null && capacity < activity.getRegisteredCount()) {
            throw new IllegalArgumentException("名额不能少于已报名的 " + activity.getRegisteredCount() + " 人");
        }
        ActivityRules.Level level = ActivityRules.levelOf(user.getRole(), user.getOperatorScope());
        int xp = request.rewardXp() == null ? nz(activity.getRewardXp()) : request.rewardXp();
        int coins = request.rewardCoins() == null ? nz(activity.getRewardCoins()) : request.rewardCoins();
        if (level == null) throw new AccessDeniedException("当前身份不能编辑官方活动");
        ActivityRules.checkReward(level, xp, coins);
        boolean scheduleChanged = !startsAt.equals(activity.getStartsAt()) || !endsAt.equals(activity.getEndsAt())
                || (request.location() != null && !request.location().trim().equals(activity.getLocation()));
        LambdaUpdateWrapper<Activity> update = new LambdaUpdateWrapper<Activity>().eq(Activity::getId, id)
                .set(Activity::getStartsAt, startsAt).set(Activity::getEndsAt, endsAt)
                .set(Activity::getSignupDeadline, deadline).set(Activity::getCapacity, capacity)
                .set(Activity::getRewardXp, xp).set(Activity::getRewardCoins, coins)
                .set(Activity::getUpdatedAt, now);
        if (request.title() != null && !request.title().isBlank()) update.set(Activity::getTitle, request.title().trim());
        if (request.organizerName() != null && !request.organizerName().isBlank()) update.set(Activity::getOrganizerName, request.organizerName().trim());
        if (request.coverUrl() != null) update.set(Activity::getCoverUrl, blankToNull(request.coverUrl()));
        if (request.description() != null && !request.description().isBlank()) update.set(Activity::getDescription, request.description().trim());
        if (request.notice() != null) update.set(Activity::getNotice, blankToNull(request.notice()));
        if (request.location() != null && !request.location().isBlank()) update.set(Activity::getLocation, request.location().trim());
        if (request.waitlistEnabled() != null) update.set(Activity::getWaitlistEnabled, request.waitlistEnabled());
        activityMapper.update(null, update);
        Activity updated = activityMapper.lockById(id);
        if (updated.getCapacity() == null || updated.getCapacity() > updated.getRegisteredCount()) {
            int seats = updated.getCapacity() == null ? Integer.MAX_VALUE : updated.getCapacity() - updated.getRegisteredCount();
            promoteWaitlist(updated, seats, now);
        }
        if (scheduleChanged) {
            notifications.notify(activeUserIds(id), "ACTIVITY_CHANGED", "活动时间或地点有变化",
                    "「" + updated.getTitle() + "」改为 " + ActivityRules.DAY_TIME.format(updated.getStartsAt())
                            + " 开始，地点：" + updated.getLocation() + "。",
                    id, "changed:" + id + ":" + now);
        }
        audit.record(actor, "ACTIVITY_UPDATE", "activity", id, scheduleChanged ? "schedule changed" : "details changed");
        return view(updated, user);
    }

    /** 取消活动：已签到的奖励保留；通知所有报名者。 */
    @Transactional
    public ActivityDtos.ActivityView cancelActivity(long id, ActivityDtos.ReasonRequest request, CurrentUser actor) {
        User user = activeUser(actor.id());
        Activity activity = managed(id, user, true);
        if ("CANCELLED".equals(activity.getStatus())) return view(activity, user);
        LocalDateTime now = AppTime.nowLocal();
        if (!now.isBefore(activity.getEndsAt())) throw new StateConflictException("活动已结束，不能取消");
        String reason = request.reason().trim();
        activityMapper.update(null, new LambdaUpdateWrapper<Activity>().eq(Activity::getId, id)
                .set(Activity::getStatus, "CANCELLED").set(Activity::getCancelReason, reason)
                .set(Activity::getCancelledAt, now).set(Activity::getUpdatedAt, now));
        notifications.notify(activeUserIds(id), "ACTIVITY_CANCELLED", "活动已取消",
                "「" + activity.getTitle() + "」已取消，原因：" + reason + "。", id, "cancelled:" + id);
        audit.recordRequired(actor, "ACTIVITY_CANCEL", "activity", id, reason);
        return view(activityMapper.selectById(id), user);
    }

    private void promoteWaitlist(Activity activity, int seats, LocalDateTime now) {
        int promoted = 0;
        while (promoted < seats) {
            ActivityRegistration next = registrationMapper.firstWaitlisted(activity.getId());
            if (next == null) break;
            registrationMapper.update(null, new LambdaUpdateWrapper<ActivityRegistration>()
                    .eq(ActivityRegistration::getId, next.getId())
                    .eq(ActivityRegistration::getStatus, "WAITLISTED")
                    .set(ActivityRegistration::getStatus, "REGISTERED")
                    .set(ActivityRegistration::getUpdatedAt, now));
            notifications.notify(List.of(next.getUserId()), "WAITLIST_PROMOTED", "候补转正",
                    "「" + activity.getTitle() + "」有空位了，你已从候补转为已报名。", activity.getId(), "promoted:" + next.getId() + ":" + now);
            promoted++;
        }
        if (promoted > 0) adjustCounts(activity, promoted, -promoted, now);
    }

    private List<Long> activeUserIds(long activityId) {
        return registrationMapper.selectList(new LambdaQueryWrapper<ActivityRegistration>()
                        .select(ActivityRegistration::getUserId)
                        .eq(ActivityRegistration::getActivityId, activityId)
                        .in(ActivityRegistration::getStatus, ACTIVE))
                .stream().map(ActivityRegistration::getUserId).toList();
    }

    /** 发布者本人或管理员；lock=true 时锁住活动行（改名额、状态时）。 */
    private Activity managed(long id, User user, boolean lock) {
        Activity activity = lock ? activityMapper.lockById(id) : activityMapper.selectById(id);
        if (activity == null || !activity.getSchoolId().equals(schoolOf(user))) throw new NoSuchElementException("活动不存在");
        if (!canManage(activity, user)) throw new AccessDeniedException("只有活动发布者可以管理这个活动");
        return activity;
    }

    private ActivityRegistration ownRegistration(long activityId, long registrationId) {
        ActivityRegistration registration = registrationMapper.selectById(registrationId);
        if (registration == null || registration.getActivityId() != activityId) throw new NoSuchElementException("报名记录不存在");
        return registration;
    }

    private ActivityDtos.RosterEntry rosterEntry(long registrationId) {
        ActivityRegistration row = registrationMapper.selectById(registrationId);
        User attendee = userMapper.selectById(row.getUserId());
        LocalDateTime now = AppTime.nowLocal();
        return new ActivityDtos.RosterEntry(row.getId(), attendee.getPublicUid(), attendee.getNickname(), attendee.getAvatarUrl(),
                attendee.getCollege(), attendee.getMajor(), attendee.getGrade(), row.getStatus(),
                AppTime.toOffset(row.getQueuedAt()), AppTime.toOffset(row.getCheckedInAt()), row.getCheckinMethod(),
                nz(row.getRewardXpGranted()), nz(row.getRewardCoinsGranted()), AppTime.toOffset(row.getCheckinRevokedAt()),
                row.getRevokeReason(), row.getCheckedInAt() != null && now.isBefore(row.getCheckedInAt().plus(REVOKE_WINDOW)));
    }

    private static int nz(Integer value) { return value == null ? 0 : value; }

    private static long parseId(String value, String message) {
        if (!value.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException(message);
        return Long.parseLong(value);
    }

    private static boolean canManage(Activity activity, User user) {
        return user != null && ("ADMIN".equals(user.getRole()) || user.getId().equals(activity.getOrganizerId()));
    }

    // ---- 组装 ----

    private ActivityDtos.ActivityPage page(List<Activity> rows, int limit, String tab, User user) {
        boolean more = rows.size() > limit;
        List<Activity> selected = more ? rows.subList(0, limit) : rows;
        String next = more ? encode(tab, selected.getLast()) : null;
        return new ActivityDtos.ActivityPage(views(selected, user), next, more, limit);
    }

    private ActivityDtos.ActivityView view(Activity activity, User viewer) {
        return views(List.of(activity), viewer).getFirst();
    }

    private List<ActivityDtos.ActivityView> views(List<Activity> activities, User viewer) {
        if (activities.isEmpty()) return List.of();
        List<Long> ids = activities.stream().map(Activity::getId).toList();
        Set<Long> organizerIds = activities.stream().map(Activity::getOrganizerId).collect(Collectors.toSet());
        Map<Long, User> organizers = userMapper.selectBatchIds(organizerIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        Map<Long, ActivityRegistration> mine = viewer == null ? Map.of()
                : registrationMapper.selectList(new LambdaQueryWrapper<ActivityRegistration>()
                        .eq(ActivityRegistration::getUserId, viewer.getId())
                        .in(ActivityRegistration::getActivityId, ids)).stream()
                .collect(Collectors.toMap(ActivityRegistration::getActivityId, Function.identity()));
        Map<Long, List<ActivityDtos.AttendeeView>> attendees = recentAttendees(ids);
        Map<Long, String> collegeNames = names(activities.stream().map(Activity::getAudienceCollegeId), true);
        Map<Long, String> majorNames = names(activities.stream().map(Activity::getAudienceMajorId), false);
        LocalDateTime now = AppTime.nowLocal();

        List<ActivityDtos.ActivityView> views = new ArrayList<>();
        for (Activity activity : activities) {
            String label = audienceLabel(activity, collegeNames, majorNames);
            ActivityRegistration registration = mine.get(activity.getId());
            String myStatus = registration == null ? "NONE" : registration.getStatus();
            Integer position = "WAITLISTED".equals(myStatus)
                    ? registrationMapper.waitlistPosition(activity.getId(), registration.getQueuedAt(), registration.getId())
                    : null;
            String reason = viewer == null ? "登录后报名"
                    : ActivityRules.ineligibleReason(activity, schoolOf(viewer), viewer.getCollegeId(), viewer.getMajorId(),
                    viewer.getEntryYear(), label);
            User organizer = organizers.get(activity.getOrganizerId());
            views.add(new ActivityDtos.ActivityView(
                    activity.getId(), activity.getTitle(), activity.getCategory(), activity.getCoverUrl(),
                    activity.getDescription(), activity.getNotice(), activity.getLocation(),
                    AppTime.toOffset(activity.getStartsAt()), AppTime.toOffset(activity.getEndsAt()),
                    AppTime.toOffset(activity.getSignupDeadline()), activity.getOrganizerName(),
                    organizer == null ? null : new ActivityDtos.OrganizerView(organizer.getId(), organizer.getPublicUid(),
                            organizer.getNickname(), organizer.getAvatarUrl(), organizer.getRole()),
                    activity.getAudienceScope(), activity.getAudienceCollegeId(), activity.getAudienceMajorId(),
                    activity.getAudienceEntryYear(), label, activity.getCapacity(),
                    activity.getRegisteredCount(), activity.getWaitlistCount(),
                    Boolean.TRUE.equals(activity.getWaitlistEnabled()), activity.getRewardXp(), activity.getRewardCoins(),
                    ActivityRules.phase(activity.getStatus(), activity.getSignupDeadline(), activity.getStartsAt(),
                            activity.getEndsAt(), now),
                    myStatus, position, reason == null, reason,
                    attendees.getOrDefault(activity.getId(), List.of()), AppTime.toOffset(activity.getCreatedAt()),
                    registration != null && registration.getCheckedInAt() != null,
                    registration == null ? null : AppTime.toOffset(registration.getCheckedInAt()),
                    "PUBLISHED".equals(activity.getStatus())
                            && CheckinTokens.windowOpen(activity.getStartsAt(), activity.getEndsAt(), now),
                    canManage(activity, viewer),
                    Boolean.TRUE.equals(activity.getRequireApproval()), activity.getCancelReason(),
                    registration != null && registration.getCheckinRevokedAt() != null));
        }
        return views;
    }

    private Map<Long, List<ActivityDtos.AttendeeView>> recentAttendees(List<Long> activityIds) {
        List<ActivityRegistration> rows = registrationMapper.selectList(new LambdaQueryWrapper<ActivityRegistration>()
                .in(ActivityRegistration::getActivityId, activityIds)
                .eq(ActivityRegistration::getStatus, "REGISTERED")
                .orderByDesc(ActivityRegistration::getQueuedAt)
                .last("LIMIT " + Math.min(400, activityIds.size() * 40)));
        Map<Long, List<Long>> userIds = new HashMap<>();
        for (ActivityRegistration row : rows) {
            List<Long> list = userIds.computeIfAbsent(row.getActivityId(), key -> new ArrayList<>());
            if (list.size() < ATTENDEE_PREVIEW) list.add(row.getUserId());
        }
        Set<Long> all = userIds.values().stream().flatMap(List::stream).collect(Collectors.toSet());
        if (all.isEmpty()) return Map.of();
        Map<Long, User> users = userMapper.selectBatchIds(all).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        Map<Long, List<ActivityDtos.AttendeeView>> result = new HashMap<>();
        userIds.forEach((activityId, ids) -> result.put(activityId, ids.stream().map(users::get).filter(Objects::nonNull)
                .filter(user -> "active".equals(user.getStatus()))
                .map(user -> new ActivityDtos.AttendeeView(user.getPublicUid(), user.getNickname(), user.getAvatarUrl()))
                .toList()));
        return result;
    }

    private Map<Long, String> names(java.util.stream.Stream<Long> ids, boolean college) {
        Set<Long> set = ids.filter(Objects::nonNull).collect(Collectors.toSet());
        if (set.isEmpty()) return Map.of();
        return college
                ? collegeMapper.selectBatchIds(set).stream().collect(Collectors.toMap(College::getId, College::getName))
                : majorMapper.selectBatchIds(set).stream().collect(Collectors.toMap(Major::getId, Major::getName));
    }

    private String audienceLabel(Activity activity) {
        return audienceLabel(activity, names(java.util.stream.Stream.of(activity.getAudienceCollegeId()), true),
                names(java.util.stream.Stream.of(activity.getAudienceMajorId()), false));
    }

    private static String audienceLabel(Activity activity, Map<Long, String> colleges, Map<Long, String> majors) {
        String base = switch (activity.getAudienceScope()) {
            case "COLLEGE" -> colleges.getOrDefault(activity.getAudienceCollegeId(), "本院");
            case "MAJOR" -> majors.getOrDefault(activity.getAudienceMajorId(), "本专业");
            default -> "全校";
        };
        return activity.getAudienceEntryYear() == null ? base : base + " · " + activity.getAudienceEntryYear() + " 级";
    }

    private void adjustCounts(Activity activity, int registeredDelta, int waitlistDelta, LocalDateTime now) {
        int registered = Math.max(0, activity.getRegisteredCount() + registeredDelta);
        int waitlist = Math.max(0, activity.getWaitlistCount() + waitlistDelta);
        activityMapper.update(null, new LambdaUpdateWrapper<Activity>().eq(Activity::getId, activity.getId())
                .set(Activity::getRegisteredCount, registered)
                .set(Activity::getWaitlistCount, waitlist)
                .set(Activity::getUpdatedAt, now));
        activity.setRegisteredCount(registered);
        activity.setWaitlistCount(waitlist);
    }

    private Activity lockVisible(long id, User user) {
        Activity activity = activityMapper.lockById(id);
        if (activity == null || !activity.getSchoolId().equals(schoolOf(user))) {
            throw new NoSuchElementException("活动不存在");
        }
        return activity;
    }

    private User activeUser(Long id) {
        User user = id == null ? null : userMapper.selectById(id);
        if (user == null) throw new NoSuchElementException("用户不存在");
        if (!"active".equals(user.getStatus())) throw new AccessDeniedException("账号不可用");
        return user;
    }

    private long schoolOf(User user) {
        return user.getSchoolId() == null ? properties.defaultSchoolId() : user.getSchoolId();
    }

    private static int limit(Integer requested) {
        if (requested == null) return 20;
        if (requested < 1) throw new IllegalArgumentException("分页大小必须大于零");
        return Math.min(requested, 50);
    }

    private record Position(LocalDateTime startsAt, long id) {}

    private static String encode(String tab, Activity last) {
        String raw = "act1|" + tab + "|" + DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(last.getStartsAt()) + "|" + last.getId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static Position decode(String cursor, String tab) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            if (cursor.length() > 160) throw new IllegalArgumentException();
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 4 || !"act1".equals(parts[0]) || !tab.equals(parts[1]) || !parts[3].matches("[1-9][0-9]{0,18}")) {
                throw new IllegalArgumentException();
            }
            return new Position(LocalDateTime.parse(parts[2], DateTimeFormatter.ISO_LOCAL_DATE_TIME), Long.parseLong(parts[3]));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("活动分页游标不合法");
        }
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
