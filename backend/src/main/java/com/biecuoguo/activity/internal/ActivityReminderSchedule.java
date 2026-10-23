package com.biecuoguo.activity.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.biecuoguo.config.AppTime;
import com.biecuoguo.notification.api.NotificationApi;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 活动开始提醒：每分钟扫一次 24 小时内开始的活动，给已报名者写站内通知。
 * 多实例同时扫也不会重复，靠通知表的 (user_id, dedupe_key) 唯一键。
 */
@Component
@ConditionalOnProperty(prefix = "app.activity.reminders", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ActivityReminderSchedule {
    private static final Logger log = LoggerFactory.getLogger(ActivityReminderSchedule.class);
    private final ActivityMapper activityMapper;
    private final ActivityRegistrationMapper registrationMapper;
    private final NotificationApi notifications;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "activity-reminders");
        thread.setDaemon(true);
        return thread;
    });

    public ActivityReminderSchedule(ActivityMapper activityMapper, ActivityRegistrationMapper registrationMapper,
                                    NotificationApi notifications) {
        this.activityMapper = activityMapper;
        this.registrationMapper = registrationMapper;
        this.notifications = notifications;
    }

    @PostConstruct
    void start() {
        executor.scheduleWithFixedDelay(() -> {
            try {
                sendDue();
            } catch (RuntimeException unavailable) {
                log.warn("Activity reminder scan failed code=ACTIVITY_REMINDER_UNAVAILABLE");
            }
        }, 30, 60, TimeUnit.SECONDS);
    }

    @PreDestroy
    void close() { executor.shutdownNow(); }

    void sendDue() {
        LocalDateTime now = AppTime.nowLocal();
        List<Activity> soon = activityMapper.selectList(new LambdaQueryWrapper<Activity>()
                .eq(Activity::getStatus, "PUBLISHED")
                .gt(Activity::getStartsAt, now)
                .le(Activity::getStartsAt, now.plusHours(24))
                .orderByAsc(Activity::getStartsAt)
                .last("LIMIT 50"));
        for (Activity activity : soon) {
            List<Long> users = registrationMapper.selectList(new LambdaQueryWrapper<ActivityRegistration>()
                            .select(ActivityRegistration::getUserId)
                            .eq(ActivityRegistration::getActivityId, activity.getId())
                            .eq(ActivityRegistration::getStatus, "REGISTERED"))
                    .stream().map(ActivityRegistration::getUserId).toList();
            if (users.isEmpty()) continue;
            boolean withinHour = !activity.getStartsAt().isAfter(now.plus(Duration.ofHours(1)));
            String when = ActivityRules.TIME.format(activity.getStartsAt());
            String day = ActivityRules.DAY_TIME.format(activity.getStartsAt());
            if (withinHour) {
                notifications.notify(users, "ACTIVITY_REMINDER", "活动 1 小时内开始",
                        "「" + activity.getTitle() + "」" + when + " 在" + activity.getLocation() + "开始，记得到场签到。",
                        activity.getId(), "reminder-1h:" + activity.getId());
            } else {
                notifications.notify(users, "ACTIVITY_REMINDER", "明天别忘了参加活动",
                        "「" + activity.getTitle() + "」将于 " + day + " 在" + activity.getLocation() + "开始。",
                        activity.getId(), "reminder-24h:" + activity.getId());
            }
        }
    }
}
