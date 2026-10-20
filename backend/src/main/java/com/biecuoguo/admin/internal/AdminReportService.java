package com.biecuoguo.admin.internal;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台报表：最近 N 天的新增用户 / 发布帖子。
 *
 * <p>数据库只返回有数据的日期，这里补齐缺失日期为 0 —— 前端不需要处理「断点」，
 * 柱状图也不会因为某天没人注册就整段错位。
 */
@Service
public class AdminReportService {

    private final AdminReportMapper reportMapper;

    public AdminReportService(AdminReportMapper reportMapper) {
        this.reportMapper = reportMapper;
    }

    public ReportView daily(int days) {
        int safeDays = Math.max(1, Math.min(days, 90));
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(safeDays - 1L);
        LocalDateTime since = from.atStartOfDay();

        Map<String, Long> users = toMap(reportMapper.dailyNewUsers(since));
        Map<String, Long> posts = toMap(reportMapper.dailyPublishedPosts(since));

        List<DailyPoint> points = new ArrayList<>();
        long userTotal = 0;
        long postTotal = 0;
        for (int i = 0; i < safeDays; i++) {
            String day = from.plusDays(i).toString();
            long u = users.getOrDefault(day, 0L);
            long p = posts.getOrDefault(day, 0L);
            userTotal += u;
            postTotal += p;
            points.add(new DailyPoint(day, u, p));
        }
        return new ReportView(safeDays, points, userTotal, postTotal);
    }

    private static Map<String, Long> toMap(List<Map<String, Object>> rows) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object day = row.get("day");
            Object total = row.get("total");
            if (day == null || total == null) continue;
            result.put(String.valueOf(day), ((Number) total).longValue());
        }
        return result;
    }

    public record DailyPoint(String day, long newUsers, long publishedPosts) {
    }

    public record ReportView(int days, List<DailyPoint> points, long newUsersTotal, long postsTotal) {
    }
}
