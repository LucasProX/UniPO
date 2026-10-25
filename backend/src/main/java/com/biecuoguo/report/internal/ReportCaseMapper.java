package com.biecuoguo.report.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface ReportCaseMapper extends BaseMapper<ReportCase> {

    /** 新举报并入未结案的单：只加计数和更新时间、摘录，不动 version（不打断正在处理的审核员）。 */
    @Update("""
            UPDATE report_cases SET report_count = report_count + 1, last_reported_at = #{at},
                   latest_snapshot = COALESCE(#{snapshot}, latest_snapshot)
            WHERE id = #{id}
            """)
    int addReport(@Param("id") long id, @Param("at") LocalDateTime at, @Param("snapshot") String snapshot);
}
