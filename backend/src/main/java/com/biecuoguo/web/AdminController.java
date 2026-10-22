package com.biecuoguo.web;

import com.biecuoguo.admin.api.AdminAuditApi;
import com.biecuoguo.domain.User;
import com.biecuoguo.dto.AdminDtos;
import com.biecuoguo.security.SecurityUtils;
import com.biecuoguo.service.AdminService;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AdminService adminService;
    private final AdminAuditApi auditApi;

    public AdminController(AdminService adminService, AdminAuditApi auditApi) {
        this.adminService = adminService;
        this.auditApi = auditApi;
    }

    @GetMapping("/users")
    public ApiResponse<List<User>> users() {
        return ApiResponse.ok(adminService.users());
    }

    @PutMapping("/users/{id}/status")
    public ApiResponse<User> updateUserStatus(@PathVariable Long id, @Valid @RequestBody AdminDtos.UserStatusRequest request) {
        User updated = adminService.updateUserStatus(id, request);
        auditApi.record(SecurityUtils.currentUser(), "user.status.update", "user", id, "status=" + request.status());
        return ApiResponse.ok(updated);
    }

    @PutMapping("/users/{id}/role")
    public ApiResponse<User> updateUserRole(@PathVariable Long id, @Valid @RequestBody AdminDtos.UserRoleRequest request) {
        User updated = adminService.updateUserRole(id, request);
        auditApi.record(SecurityUtils.currentUser(), "user.role.update", "user", id,
                "role=" + request.role() + " scope=" + request.operatorScope());
        return ApiResponse.ok(updated);
    }

    @GetMapping("/analytics/overview")
    public ApiResponse<AdminDtos.AnalyticsOverview> overview() {
        return ApiResponse.ok(adminService.overview());
    }
}
