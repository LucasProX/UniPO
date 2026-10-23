package com.biecuoguo.achievement.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 活动参与徽章：按签到有效的累计场次推导，不落 user_badges。
 * 编码与 V30 种入的 badges 行一一对应。
 */
public final class AttendanceBadges {
    public static final Map<String, Integer> THRESHOLDS = thresholds();
    /** 推导获得时间最多需要的签到条数。 */
    public static final int MAX_THRESHOLD = 15;

    private AttendanceBadges() {}

    private static Map<String, Integer> thresholds() {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("activity-1", 1);
        map.put("activity-5", 5);
        map.put("activity-15", MAX_THRESHOLD);
        return java.util.Collections.unmodifiableMap(map);
    }

    public static boolean isAttendanceBadge(String code) {
        return THRESHOLDS.containsKey(code);
    }

    /** 恰好在第 count 场达到门槛的徽章。 */
    public static Optional<String> reachedAt(int count) {
        return THRESHOLDS.entrySet().stream().filter(entry -> entry.getValue() == count)
                .map(Map.Entry::getKey).findFirst();
    }
}
