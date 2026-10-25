package com.biecuoguo.permission.internal;

import com.biecuoguo.permission.api.RoleGrantDtos;
import com.biecuoguo.permission.api.ScopeType;
import com.biecuoguo.security.SecurityUtils;
import com.biecuoguo.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class RoleGrantController {
    private final RoleGrantService service;

    public RoleGrantController(RoleGrantService service) {
        this.service = service;
    }

    @GetMapping("/api/admin/role-grants")
    public ApiResponse<List<RoleGrantDtos.GrantView>> list(@RequestParam(required = false) String userUid,
                                                           @RequestParam(required = false) ScopeType scopeType,
                                                           @RequestParam(required = false) Long scopeId,
                                                           @RequestParam(defaultValue = "false") boolean includeInactive) {
        return ApiResponse.ok(service.list(SecurityUtils.currentUser(), userUid, scopeType, scopeId, includeInactive));
    }

    @PostMapping("/api/admin/role-grants")
    public ApiResponse<RoleGrantDtos.GrantView> grant(@Valid @RequestBody RoleGrantDtos.GrantRequest request) {
        return ApiResponse.ok(service.grant(SecurityUtils.currentUser(), request));
    }

    @PostMapping("/api/admin/role-grants/{id}/revoke")
    public ApiResponse<RoleGrantDtos.GrantView> revoke(@PathVariable long id,
                                                       @Valid @RequestBody RoleGrantDtos.RevokeRequest request) {
        return ApiResponse.ok(service.revoke(SecurityUtils.currentUser(), id, request));
    }

    @GetMapping("/api/users/me/capabilities")
    public ApiResponse<RoleGrantDtos.CapabilitiesView> capabilities() {
        return ApiResponse.ok(service.capabilities(SecurityUtils.currentUser()));
    }
}
