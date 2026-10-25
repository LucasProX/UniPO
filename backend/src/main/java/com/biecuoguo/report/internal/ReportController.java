package com.biecuoguo.report.internal;

import com.biecuoguo.report.api.ReportDtos;
import com.biecuoguo.security.SecurityUtils;
import com.biecuoguo.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** 举报入口。旧的举报用户接口保留原路径与返回值（举报单 id），帖子和评论返回受理回执。 */
@RestController
public class ReportController {
    private final ReportService service;

    public ReportController(ReportService service) {
        this.service = service;
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
