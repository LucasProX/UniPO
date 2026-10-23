package com.biecuoguo.achievement.internal;

import com.biecuoguo.achievement.api.AchievementDtos;
import com.biecuoguo.security.SecurityUtils;
import com.biecuoguo.web.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/achievements")
public class AchievementController {

    private final AchievementService achievementService;

    public AchievementController(AchievementService achievementService) {
        this.achievementService = achievementService;
    }

    @GetMapping("/me")
    public ApiResponse<AchievementDtos.AchievementView> me() {
        return ApiResponse.ok(achievementService.view(SecurityUtils.currentUser().id()));
    }

    @GetMapping("/tasks/{taskId}")
    public ApiResponse<AchievementDtos.TaskDetailView> task(@PathVariable Long taskId) {
        return ApiResponse.ok(achievementService.detail(taskId, SecurityUtils.currentUser().id()));
    }

    @PostMapping("/tasks/{taskId}/complete")
    public ApiResponse<AchievementDtos.AchievementView> complete(@PathVariable Long taskId) {
        return ApiResponse.ok(achievementService.complete(taskId, SecurityUtils.currentUser().id()));
    }

    @DeleteMapping("/tasks/{taskId}/complete")
    public ApiResponse<AchievementDtos.AchievementView> undo(@PathVariable Long taskId) {
        return ApiResponse.ok(achievementService.undo(taskId, SecurityUtils.currentUser().id()));
    }
}
