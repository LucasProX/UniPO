package com.biecuoguo.report.internal;

import com.biecuoguo.config.AppTime;
import com.biecuoguo.permission.api.ScopeTarget;
import com.biecuoguo.report.api.ReportFlagApi;
import org.springframework.stereotype.Service;

import java.time.temporal.ChronoUnit;

@Service
public class ReportFlagService implements ReportFlagApi {
    private final ContentReportMapper mapper;

    public ReportFlagService(ContentReportMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void flag(String targetType, long targetId, long targetUserId, ScopeTarget scope, String category, String snapshot) {
        ContentReport row = new ContentReport();
        row.setSource("FILTER");
        row.setTargetType(targetType);
        row.setTargetId(targetId);
        row.setTargetUserId(targetUserId);
        row.setSchoolId(scope.schoolId());
        row.setCollegeId(scope.collegeId());
        row.setMajorId(scope.majorId());
        row.setCategory("filter:" + category);
        row.setSnapshot(ReportService.clip(snapshot));
        row.setStatus("PENDING");
        row.setCreatedAt(AppTime.nowLocal().truncatedTo(ChronoUnit.SECONDS));
        mapper.insert(row);
    }
}
