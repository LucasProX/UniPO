package com.biecuoguo.spotlight.internal;

import com.biecuoguo.security.SecurityUtils;
import com.biecuoguo.spotlight.api.SpotlightDtos;
import com.biecuoguo.web.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 后台轮播位管理。路径在 /api/admin/** 下，权限由 SecurityConfig 的 hasAnyRole 统一拦截。
 *
 * <p>目前只有 API，没有管理后台页面——先让运营链路可跑（curl / 未来的 admin 前端），
 * 等后台 UI 方案定下来再接。
 */
@RestController
@RequestMapping("/api/admin/spotlights")
public class AdminSpotlightController {

    private final SpotlightService spotlightService;

    public AdminSpotlightController(SpotlightService spotlightService) {
        this.spotlightService = spotlightService;
    }

    @GetMapping
    public ApiResponse<List<SpotlightDtos.SpotlightView>> list() {
        return ApiResponse.ok(spotlightService.listAll());
    }

    @PostMapping
    public ApiResponse<SpotlightDtos.SpotlightView> create(@RequestBody SpotlightDtos.UpsertRequest request) {
        return ApiResponse.ok(spotlightService.create(request, SecurityUtils.currentUser().id()));
    }

    @PutMapping("/{id}")
    public ApiResponse<SpotlightDtos.SpotlightView> update(@PathVariable Long id,
                                                           @RequestBody SpotlightDtos.UpsertRequest request) {
        return ApiResponse.ok(spotlightService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Boolean> delete(@PathVariable Long id) {
        spotlightService.delete(id);
        return ApiResponse.ok(true);
    }
}
