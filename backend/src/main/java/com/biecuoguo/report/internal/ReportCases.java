package com.biecuoguo.report.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 把一条举报挂到它所在对象的未结案处理单上，没有就新开一个。只依赖 Mapper，
 * 用户举报（ReportService）和系统标记（ReportFlagService）都用它，帖子模块注入后者也不会成环。
 */
@Component
class ReportCases {
    private final ReportCaseMapper cases;
    private final ContentReportMapper reports;

    ReportCases(ReportCaseMapper cases, ContentReportMapper reports) {
        this.cases = cases;
        this.reports = reports;
    }

    static String openKey(String targetType, long targetId) {
        return targetType + ":" + targetId;
    }

    void attach(ContentReport report) {
        String key = openKey(report.getTargetType(), report.getTargetId());
        ReportCase open = cases.selectOne(new LambdaQueryWrapper<ReportCase>().eq(ReportCase::getOpenKey, key));
        if (open == null) {
            ReportCase created = new ReportCase();
            created.setTargetType(report.getTargetType());
            created.setTargetId(report.getTargetId());
            created.setTargetUserId(report.getTargetUserId());
            created.setSchoolId(report.getSchoolId());
            created.setCollegeId(report.getCollegeId());
            created.setMajorId(report.getMajorId());
            created.setOpenKey(key);
            created.setStatus("PENDING");
            created.setReportCount(1);
            created.setLatestSnapshot(report.getSnapshot());
            created.setFirstReportedAt(report.getCreatedAt());
            created.setLastReportedAt(report.getCreatedAt());
            created.setVersion(0);
            created.setCreatedAt(report.getCreatedAt());
            try {
                cases.insert(created);
                link(report, created.getId());
                return;
            } catch (DuplicateKeyException race) {
                // 另一条举报同时开了单，并入它。
                open = cases.selectOne(new LambdaQueryWrapper<ReportCase>().eq(ReportCase::getOpenKey, key));
                if (open == null) throw race;
            }
        }
        cases.addReport(open.getId(), report.getCreatedAt(), report.getSnapshot());
        link(report, open.getId());
    }

    private void link(ContentReport report, long caseId) {
        report.setCaseId(caseId);
        reports.update(null, new LambdaUpdateWrapper<ContentReport>().eq(ContentReport::getId, report.getId())
                .set(ContentReport::getCaseId, caseId));
    }
}
