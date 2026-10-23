package com.biecuoguo.growth.internal;

import com.biecuoguo.growth.api.GrowthDtos;
import com.biecuoguo.security.SecurityUtils;
import com.biecuoguo.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/growth")
public class GrowthController {
    private final GrowthPlanService service;

    public GrowthController(GrowthPlanService service) {
        this.service = service;
    }

    @GetMapping("/term-plan")
    public ApiResponse<GrowthDtos.TermPlanView> termPlan(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate termStart) {
        return ApiResponse.ok(service.termPlan(SecurityUtils.currentUser().id(), termStart));
    }

    @GetMapping("/archive")
    public ApiResponse<GrowthDtos.ArchiveView> archive() {
        return ApiResponse.ok(service.archive(SecurityUtils.currentUser().id()));
    }

    @PostMapping("/tasks")
    public ApiResponse<GrowthDtos.PersonalTaskView> create(@Valid @RequestBody GrowthDtos.PersonalTaskRequest request) {
        return ApiResponse.ok(service.create(SecurityUtils.currentUser().id(), request));
    }

    @PutMapping("/tasks/{taskId}")
    public ApiResponse<GrowthDtos.PersonalTaskView> update(@PathVariable long taskId,
                                                           @Valid @RequestBody GrowthDtos.PersonalTaskRequest request) {
        return ApiResponse.ok(service.update(SecurityUtils.currentUser().id(), taskId, request));
    }

    @PostMapping("/tasks/{taskId}/complete")
    public ApiResponse<GrowthDtos.PersonalTaskView> complete(@PathVariable long taskId) {
        return ApiResponse.ok(service.setCompleted(SecurityUtils.currentUser().id(), taskId, true));
    }

    @DeleteMapping("/tasks/{taskId}/complete")
    public ApiResponse<GrowthDtos.PersonalTaskView> undo(@PathVariable long taskId) {
        return ApiResponse.ok(service.setCompleted(SecurityUtils.currentUser().id(), taskId, false));
    }

    @DeleteMapping("/tasks/{taskId}")
    public ApiResponse<GrowthDtos.PersonalTaskView> delete(@PathVariable long taskId) {
        return ApiResponse.ok(service.delete(SecurityUtils.currentUser().id(), taskId));
    }
}
