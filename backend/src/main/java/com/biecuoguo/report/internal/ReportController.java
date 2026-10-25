package com.biecuoguo.report.internal;

import com.biecuoguo.report.api.ReportCaseDtos;
import com.biecuoguo.report.api.ReportDtos;
import com.biecuoguo.security.SecurityUtils;
import com.biecuoguo.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** 举报入口。旧的举报用户接口保留原路径与返回值（举报单 id），帖子和评论返回受理回执。 */
@RestController
public class ReportController {
    private final ReportService service;
    private final ReportCaseService caseService;

    public ReportController(ReportService service, ReportCaseService caseService) {
        this.service = service;
        this.caseService = caseService;
    }

    /** 我的举报与处理结果（O03）。 */
    @GetMapping("/api/users/me/reports")
    public ApiResponse<ReportCaseDtos.MyReportPage> myReports(@RequestParam(required = false) Long beforeId) {
        return ApiResponse.ok(service.myReports(SecurityUtils.currentUser(), beforeId));
    }

    // ---- 审核员：举报处理单（O01），范围由 report.handle 授权决定 ----

    @GetMapping("/api/admin/report-cases")
    public ApiResponse<ReportCaseDtos.CasePage> cases(@RequestParam(required = false) String status,
                                                       @RequestParam(required = false) Long beforeId) {
        return ApiResponse.ok(caseService.list(SecurityUtils.currentUser(), status, beforeId));
    }

    @GetMapping("/api/admin/report-cases/{id}")
    public ApiResponse<ReportCaseDtos.CaseDetail> caseDetail(@PathVariable long id) {
        return ApiResponse.ok(caseService.detail(SecurityUtils.currentUser(), id));
    }

    @PostMapping("/api/admin/report-cases/{id}/claim")
    public ApiResponse<ReportCaseDtos.CaseView> claim(@PathVariable long id, @Valid @RequestBody ReportCaseDtos.ClaimRequest request) {
        return ApiResponse.ok(caseService.claim(SecurityUtils.currentUser(), id, request));
    }

    @PostMapping("/api/admin/report-cases/{id}/resolve")
    public ApiResponse<ReportCaseDtos.CaseView> resolve(@PathVariable long id, @Valid @RequestBody ReportCaseDtos.ResolveRequest request) {
        return ApiResponse.ok(caseService.resolve(SecurityUtils.currentUser(), id, request));
    }

    @PostMapping("/api/users/{uid}/report")
    public ApiResponse<Long> reportUser(@PathVariable String uid, @Valid @RequestBody ReportDtos.ReportRequest request) {
        return ApiResponse.ok(service.reportUser(uid, request, SecurityUtils.currentUser()).id());
    }

    @PostMapping("/api/posts/{id:\\d+}/report")
    public ApiResponse<ReportDtos.ReportReceipt> reportPost(@PathVariable long id,
                                                            @Valid @RequestBody ReportDtos.ReportRequest request) {
        return ApiResponse.ok(service.reportContent("POST", id, request, SecurityUtils.currentUser()));
    }

    @PostMapping("/api/posts/comments/{id:\\d+}/report")
    public ApiResponse<ReportDtos.ReportReceipt> reportComment(@PathVariable long id,
                                                               @Valid @RequestBody ReportDtos.ReportRequest request) {
        return ApiResponse.ok(service.reportContent("COMMENT", id, request, SecurityUtils.currentUser()));
    }
}
