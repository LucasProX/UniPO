package com.biecuoguo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 时区边界：数据库 {@code DATETIME}（无时区）<-> 接口层 {@code OffsetDateTime}（带偏移）。
 *
 * <p><b>为什么需要它</b>：MySQL 的 {@code DATETIME} 不存时区，读出来是裸的
 * {@link LocalDateTime}。直接返回给客户端，App 在不同时区会显示错时间，也做不出
 * 「3 分钟前」这类相对时间——客户端不知道那个裸时间属于哪个时区。
 *
 * <p><b>约定</b>：数据库里的时间**一律按 {@code app.time-zone} 解释**（默认
 * Asia/Shanghai）。这是显式约定而非隐式依赖 JVM 默认时区，换服务器时区不改变接口语义。
 *
 * <p>只在两个方向调用：出参用 {@link #toOffset(LocalDateTime)}（在 DTO 的
 * {@code from()} 里），入参用 {@link #toLocal(OffsetDateTime)}（Service 落库前）。
 * 不要在业务逻辑中间到处转换。
 *
 * <p>做成静态工具是因为 DTO 的 {@code from()} 是静态工厂，注入不进去；构造函数由
 * Spring 调用一次完成初始化，之后全局只读。
 */
@Component
public class AppTime {

    private static volatile ZoneId zone = ZoneId.of("Asia/Shanghai");

    public AppTime(@Value("${app.time-zone:Asia/Shanghai}") String zoneId) {
        AppTime.zone = ZoneId.of(zoneId);
    }

    public static ZoneId zone() {
        return zone;
    }

    /** 写进数据库用的当前时间（无时区）。 */
    public static LocalDateTime nowLocal() {
        return LocalDateTime.now(zone);
    }

    /** 接口出参用的当前时间（带偏移）。 */
    public static OffsetDateTime nowOffset() {
        return OffsetDateTime.now(zone);
    }

    /** 数据库时间 -> 接口时间。null 安全。 */
    public static OffsetDateTime toOffset(LocalDateTime local) {
        if (local == null) {
            return null;
        }
        return local.atOffset(offsetOf(local));
    }

    /** 接口时间 -> 数据库时间（换算到 app 时区）。null 安全。 */
    public static LocalDateTime toLocal(OffsetDateTime offset) {
        if (offset == null) {
            return null;
        }
        return offset.atZoneSameInstant(zone).toLocalDateTime();
    }

    private static ZoneOffset offsetOf(LocalDateTime local) {
        return zone.getRules().getOffset(local);
    }
}