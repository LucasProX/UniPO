package com.biecuoguo.web;

import com.biecuoguo.domain.User;
import com.biecuoguo.dto.AdminDtos;
import com.biecuoguo.service.AdminService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/users")
    public ApiResponse<List<User>> users() {
        return ApiResponse.ok(adminService.users());
    }

    @PutMapping("/users/{id}/status")
    public ApiResponse<User> updateUserStatus(@PathVariable Long id, @RequestBody AdminDtos.UserStatusRequest request) {
        return ApiResponse.ok(adminService.updateUserStatus(id, request));
    }

    @PutMapping("/users/{id}/role")
    public ApiResponse<User> updateUserRole(@PathVariable Long id, @RequestBody AdminDtos.UserRoleRequest request) {
        return ApiResponse.ok(adminService.updateUserRole(id, request));
    }

    @GetMapping("/analytics/overview")
    public ApiResponse<AdminDtos.AnalyticsOverview> overview() {
        return ApiResponse.ok(adminService.overview());
    }
}
