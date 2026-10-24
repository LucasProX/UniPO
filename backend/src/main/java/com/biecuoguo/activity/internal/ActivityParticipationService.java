package com.biecuoguo.activity.internal;

import com.biecuoguo.activity.api.ActivityParticipationApi;
import com.biecuoguo.config.AppTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 只依赖本模块 Mapper，不依赖 ActivityService，避免与成长模块形成 Bean 循环。 */
@Service
public class ActivityParticipationService implements ActivityParticipationApi {
    private final ActivityMapper activityMapper;
    private final ActivityRegistrationMapper registrationMapper;

    public ActivityParticipationService(ActivityMapper activityMapper, ActivityRegistrationMapper registrationMapper) {
        this.activityMapper = activityMapper;
        this.registrationMapper = registrationMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Participation> participations(long userId, LocalDateTime from, LocalDateTime to) {
        return assemble(registrationMapper.selectInRange(userId, from, to), false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Participation> attended(long userId, int limit) {
        return assemble(registrationMapper.selectAttended(userId, Math.max(1, Math.min(500, limit))), true);
    }

    @Override
    @Transactional(readOnly = true)
    public AttendanceSummary attendance(long userId, int earliest) {
        return new AttendanceSummary(registrationMapper.countAttended(userId),
                registrationMapper.earliestCheckins(userId, Math.max(1, earliest)));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, ActivityRef> refs(java.util.Collection<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return activityMapper.selectBatchIds(ids).stream().collect(Collectors.toMap(Activity::getId,
                activity -> new ActivityRef(activity.getId(), activity.getTitle(), activity.getSchoolId(), activity.getStatus())));
    }

    private List<Participation> assemble(List<ActivityRegistration> registrations, boolean newestFirst) {
        if (registrations.isEmpty()) return List.of();
        Map<Long, Activity> activities = activityMapper.selectBatchIds(
                        registrations.stream().map(ActivityRegistration::getActivityId).distinct().toList())
                .stream().collect(Collectors.toMap(Activity::getId, Function.identity()));
        Comparator<Participation> order = Comparator.comparing(Participation::startsAt)
                .thenComparingLong(Participation::activityId);
        return registrations.stream()
                .filter(registration -> activities.containsKey(registration.getActivityId()))
                .map(registration -> view(activities.get(registration.getActivityId()), registration))
                .sorted(newestFirst ? order.reversed() : order)
                .toList();
    }

    private static Participation view(Activity activity, ActivityRegistration registration) {
        boolean checkedIn = registration.getCheckedInAt() != null;
        return new Participation(
                activity.getId(), activity.getTitle(), activity.getCategory(), activity.getLocation(),
                activity.getOrganizerName(),
                AppTime.toOffset(activity.getStartsAt()), AppTime.toOffset(activity.getEndsAt()),
                activity.getStatus(), registration.getStatus(), checkedIn,
                checkedIn ? AppTime.toOffset(registration.getCheckedInAt()) : null,
                registration.getCheckinRevokedAt() != null,
                nz(registration.getRewardXpGranted()), nz(registration.getRewardCoinsGranted()));
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
