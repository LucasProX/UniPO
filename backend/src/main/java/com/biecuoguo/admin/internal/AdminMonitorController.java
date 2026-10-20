package com.biecuoguo.admin.internal;

import com.biecuoguo.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/monitor")
public class AdminMonitorController {

    private final AdminMonitorService monitorService;

    public AdminMonitorController(AdminMonitorService monitorService) {
        this.monitorService = monitorService;
    }

    @GetMapping("/summary")
    public ApiResponse<AdminMonitorService.MonitorView> summary() {
        return ApiResponse.ok(monitorService.summary());
    }
}
