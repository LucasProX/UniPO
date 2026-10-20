package com.biecuoguo.admin.internal;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 报表用的只读聚合查询。
 *
 * <p>为什么单独写 SQL 而不走各模块 Mapper：这是「跨模块的统计口径」，
 * 硬塞进 post/user 模块会把后台报表需求变成它们的负担。等报表复杂到需要
 * 独立数仓时，这里会被替换掉，接口形状保持不变。
 */
public interface AdminReportMapper {

    @Select("""
            SELECT DATE(created_at) AS day, COUNT(*) AS total
            FROM users
            WHERE created_at >= #{since}
            GROUP BY DATE(created_at)
            ORDER BY day
            """)
    List<Map<String, Object>> dailyNewUsers(@Param("since") LocalDateTime since);

    @Select("""
            SELECT DATE(published_at) AS day, COUNT(*) AS total
            FROM posts
            WHERE status = 'published' AND published_at >= #{since}
            GROUP BY DATE(published_at)
            ORDER BY day
            """)
    List<Map<String, Object>> dailyPublishedPosts(@Param("since") LocalDateTime since);
}
