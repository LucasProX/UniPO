package com.biecuoguo.web;

import com.biecuoguo.domain.User;
import com.biecuoguo.dto.AdminDtos;
import com.biecuoguo.dto.CommentDtos;
import com.biecuoguo.dto.NoticeDtos;
import com.biecuoguo.security.SecurityUtils;
import com.biecuoguo.service.AdminService;
import com.biecuoguo.service.CommentService;
import com.biecuoguo.service.NoticeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final NoticeService noticeService;
    private final CommentService commentService;
    private final AdminService adminService;

    public AdminController(NoticeService noticeService, CommentService commentService, AdminService adminService) {
        this.noticeService = noticeService;
        this.commentService = commentService;
        this.adminService = adminService;
    }

    @GetMapping("/notices")
    public ApiResponse<List<NoticeDtos.NoticeSummary>> notices(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String filter,
            @RequestParam(required = false) String status
    ) {
        return ApiResponse.ok(noticeService.list(new NoticeDtos.NoticeQuery(q, categoryId, filter, status), SecurityUtils.currentUser(), true));
    }

    @GetMapping("/notices/{id}")
    public ApiResponse<NoticeDtos.NoticeDetail> notice(@PathVariable Long id) {
        return ApiResponse.ok(noticeService.detail(id, SecurityUtils.currentUser(), true));
    }

    @PostMapping("/notices")
    public ApiResponse<NoticeDtos.NoticeDetail> createNotice(@Valid @RequestBody NoticeDtos.NoticeRequest request) {
        return ApiResponse.ok(noticeService.create(request, SecurityUtils.currentUser()));
    }

    @PutMapping("/notices/{id}")
    public ApiResponse<NoticeDtos.NoticeDetail> updateNotice(@PathVariable Long id, @Valid @RequestBody NoticeDtos.NoticeRequest request) {
        return ApiResponse.ok(noticeService.update(id, request, SecurityUtils.currentUser()));
    }

    @DeleteMapping("/notices/{id}")
    public ApiResponse<Boolean> deleteNotice(@PathVariable Long id) {
        noticeService.delete(id);
        return ApiResponse.ok(true);
    }

    @PostMapping("/notices/{id}/publish")
    public ApiResponse<NoticeDtos.NoticeDetail> publish(@PathVariable Long id) {
        return ApiResponse.ok(noticeService.publish(id, SecurityUtils.currentUser()));
    }

    @PostMapping("/notices/{id}/unpublish")
    public ApiResponse<NoticeDtos.NoticeDetail> unpublish(@PathVariable Long id) {
        return ApiResponse.ok(noticeService.unpublish(id, SecurityUtils.currentUser()));
    }

    @GetMapping("/comments")
    public ApiResponse<List<CommentDtos.CommentView>> comments(@RequestParam(required = false) Long noticeId, @RequestParam(required = false) String status) {
        return ApiResponse.ok(commentService.adminList(noticeId, status));
    }

    @PostMapping("/comments/{id}/hide")
    public ApiResponse<CommentDtos.CommentView> hide(@PathVariable Long id) {
        return ApiResponse.ok(commentService.hide(id));
    }

    @PostMapping("/comments/{id}/feature")
    public ApiResponse<CommentDtos.CommentView> feature(@PathVariable Long id) {
        return ApiResponse.ok(commentService.feature(id));
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
