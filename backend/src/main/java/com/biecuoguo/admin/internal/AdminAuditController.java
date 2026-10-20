package com.biecuoguo.admin.internal;

import com.biecuoguo.admin.api.AdminAuditApi;
import com.biecuoguo.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/audit-logs")
public class AdminAuditController {

    private final AdminAuditApi auditApi;

    public AdminAuditController(AdminAuditApi auditApi) {
        this.auditApi = auditApi;
    }

    @GetMapping
    public ApiResponse<List<AdminAuditApi.AuditView>> recent(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(auditApi.recent(limit));
    }
}
