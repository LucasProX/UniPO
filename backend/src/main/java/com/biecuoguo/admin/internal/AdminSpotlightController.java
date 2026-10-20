package com.biecuoguo.admin.internal;

import com.biecuoguo.admin.api.AdminAuditApi;
import com.biecuoguo.security.CurrentUser;
import com.biecuoguo.security.SecurityUtils;
import com.biecuoguo.spotlight.api.SpotlightAdminApi;
import com.biecuoguo.spotlight.api.SpotlightDtos;
import com.biecuoguo.web.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 后台轮播位管理。放在 admin 模块，依赖 spotlight.api 而不是 spotlight.internal。
 */
@RestController
@RequestMapping("/api/admin/spotlights")
public class AdminSpotlightController {

    private final SpotlightAdminApi spotlightAdminApi;
    private final AdminAuditApi auditApi;

    public AdminSpotlightController(SpotlightAdminApi spotlightAdminApi, AdminAuditApi auditApi) {
        this.spotlightAdminApi = spotlightAdminApi;
        this.auditApi = auditApi;
    }

    @GetMapping
    public ApiResponse<List<SpotlightDtos.SpotlightView>> list() {
        return ApiResponse.ok(spotlightAdminApi.listAll());
    }

    @PostMapping
    public ApiResponse<SpotlightDtos.SpotlightView> create(@RequestBody SpotlightDtos.UpsertRequest request) {
        CurrentUser actor = SecurityUtils.currentUser();
        SpotlightDtos.SpotlightView created = spotlightAdminApi.create(request, actor.id());
        auditApi.record(actor, "spotlight.create", "spotlight", created.id(), "title=" + created.title());
        return ApiResponse.ok(created);
    }

    @PutMapping("/{id}")
    public ApiResponse<SpotlightDtos.SpotlightView> update(@PathVariable Long id,
                                                           @RequestBody SpotlightDtos.UpsertRequest request) {
        SpotlightDtos.SpotlightView updated = spotlightAdminApi.update(id, request);
        auditApi.record(SecurityUtils.currentUser(), "spotlight.update", "spotlight", id,
                "title=" + updated.title() + " weight=" + updated.weight() + " enabled=" + updated.enabled());
        return ApiResponse.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Boolean> delete(@PathVariable Long id) {
        spotlightAdminApi.delete(id);
        auditApi.record(SecurityUtils.currentUser(), "spotlight.delete", "spotlight", id, null);
        return ApiResponse.ok(true);
    }
}
