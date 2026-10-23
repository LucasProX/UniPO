package com.biecuoguo.activity.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.biecuoguo.activity.api.ActivityDtos;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
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

    private final ActivityMapper activityMapper;
    private final ActivityRegistrationMapper registrationMapper;
    private final UserMapper userMapper;
    private final CollegeMapper collegeMapper;
    private final MajorMapper majorMapper;
    private final AppProperties properties;

    public ActivityService(ActivityMapper activityMapper, ActivityRegistrationMapper registrationMapper,
                           UserMapper userMapper, CollegeMapper collegeMapper, MajorMapper majorMapper,
                           AppProperties properties) {
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
        activity.setRewardXp(xp);
        activity.setRewardCoins(coins);
        activity.setStatus("PUBLISHED");
        activity.setRegisteredCount(0);
        activity.setWaitlistCount(0);
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
        if (existing != null && Set.of("REGISTERED", "WAITLISTED").contains(existing.getStatus())) {
            return view(activity, user);
        }
        LocalDateTime now = AppTime.nowLocal();
        String phase = ActivityRules.phase(activity.getStatus(), activity.getSignupDeadline(), activity.getStartsAt(),
                activity.getEndsAt(), now);
        if (!"OPEN".equals(phase)) throw new StateConflictException("活动当前不能报名");
        String reason = ActivityRules.ineligibleReason(activity, schoolOf(user), user.getCollegeId(), user.getMajorId(),
                user.getEntryYear(), audienceLabel(activity));
        if (reason != null) throw new AccessDeniedException(reason);

        boolean hasSeat = activity.getCapacity() == null || activity.getRegisteredCount() < activity.getCapacity();
        if (!hasSeat && !Boolean.TRUE.equals(activity.getWaitlistEnabled())) {
            throw new StateConflictException("名额已满");
        }
        String status = hasSeat ? "REGISTERED" : "WAITLISTED";
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
        adjustCounts(activity, hasSeat ? 1 : 0, hasSeat ? 0 : 1, now);
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
        boolean wasRegistered = "REGISTERED".equals(existing.getStatus());
        registrationMapper.update(null, new LambdaUpdateWrapper<ActivityRegistration>()
                .eq(ActivityRegistration::getId, existing.getId())
                .set(ActivityRegistration::getStatus, "CANCELLED")
                .set(ActivityRegistration::getCancelledAt, now)
                .set(ActivityRegistration::getUpdatedAt, now));
        int registeredDelta = wasRegistered ? -1 : 0;
        int waitlistDelta = wasRegistered ? 0 : -1;
        if (wasRegistered && "PUBLISHED".equals(activity.getStatus())) {
            ActivityRegistration next = registrationMapper.firstWaitlisted(id);
            if (next != null) {
                registrationMapper.update(null, new LambdaUpdateWrapper<ActivityRegistration>()
                        .eq(ActivityRegistration::getId, next.getId())
                        .eq(ActivityRegistration::getStatus, "WAITLISTED")
                        .set(ActivityRegistration::getStatus, "REGISTERED")
                        .set(ActivityRegistration::getUpdatedAt, now));
                registeredDelta += 1;
                waitlistDelta -= 1;
            }
        }
        adjustCounts(activity, registeredDelta, waitlistDelta, now);
        return view(activity, user);
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
                    attendees.getOrDefault(activity.getId(), List.of()), AppTime.toOffset(activity.getCreatedAt())));
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
