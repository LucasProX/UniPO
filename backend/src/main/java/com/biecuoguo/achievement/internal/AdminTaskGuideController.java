package com.biecuoguo.achievement.internal;

import com.biecuoguo.achievement.api.AchievementDtos;
import com.biecuoguo.security.SecurityUtils;
import com.biecuoguo.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 运营工具：任务攻略关联。路径在 /api/admin/** 下，由 SecurityConfig 限定运营与管理员。 */
@RestController
@RequestMapping("/api/admin/achievement-tasks/{taskId}/guides")
public class AdminTaskGuideController {
    private final TaskGuideAdminService service;

    public AdminTaskGuideController(TaskGuideAdminService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<AchievementDtos.GuideLinkView>> list(@PathVariable long taskId) {
        return ApiResponse.ok(service.list(taskId));
    }

    @PostMapping
    public ApiResponse<List<AchievementDtos.GuideLinkView>> add(@PathVariable long taskId,
                                                               @Valid @RequestBody AchievementDtos.GuideLinkRequest request) {
        return ApiResponse.ok(service.add(taskId, request, SecurityUtils.currentUser()));
    }

    @DeleteMapping("/{postId}")
    public ApiResponse<List<AchievementDtos.GuideLinkView>> remove(@PathVariable long taskId, @PathVariable long postId) {
        return ApiResponse.ok(service.remove(taskId, postId, SecurityUtils.currentUser()));
    }
}
