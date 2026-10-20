package com.biecuoguo.admin.internal;

import com.biecuoguo.config.AppTime;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.List;

/**
 * 后台监控页的数据源：把 Actuator / Micrometer 里最常用的几项拼成一个视图。
 *
 * <p>为什么不直接让前端抓 /actuator/prometheus 解析文本：Prometheus 格式是给采集器用的，
 * 前端解析它既脆弱又浪费；这里做一个稳定的 JSON 契约，指标口径变化只改后端。
 */
@Service
public class AdminMonitorService {

    private final HealthEndpoint healthEndpoint;
    private final MeterRegistry meterRegistry;

    public AdminMonitorService(HealthEndpoint healthEndpoint, MeterRegistry meterRegistry) {
        this.healthEndpoint = healthEndpoint;
        this.meterRegistry = meterRegistry;
    }

    public MonitorView summary() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return new MonitorView(
                healthEndpoint.health().getStatus().getCode(),
                ManagementFactory.getRuntimeMXBean().getUptime() / 1000,
                heap.getUsed() / 1024 / 1024,
                heap.getMax() / 1024 / 1024,
                gauge("unipo.realtime.connections"),
                counter("unipo.realtime.events.published"),
                counter("unipo.realtime.events.delivered"),
                counter("unipo.ratelimit.blocked"),
                AppTime.nowOffset().toString()
        );
    }

    private double gauge(String name) {
        Gauge gauge = meterRegistry.find(name).gauge();
        return gauge == null ? 0 : gauge.value();
    }

    private double counter(String name) {
        List<Counter> counters = meterRegistry.find(name).counters().stream().toList();
        return counters.stream().mapToDouble(Counter::count).sum();
    }

    public record MonitorView(
            String status,
            long uptimeSeconds,
            long heapUsedMb,
            long heapMaxMb,
            double realtimeConnections,
            double eventsPublished,
            double eventsDelivered,
            double rateLimitBlocked,
            String sampledAt
    ) {
    }
}
