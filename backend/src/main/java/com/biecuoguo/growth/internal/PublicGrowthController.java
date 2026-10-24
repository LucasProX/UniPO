package com.biecuoguo.growth.internal;

import com.biecuoguo.growth.api.GrowthDtos;
import com.biecuoguo.security.CurrentUser;
import com.biecuoguo.web.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 主页上的成长展示，和公开主页一样访客可读（SecurityConfig 白名单 /api/users/*\/growth）。 */
@RestController
@RequestMapping("/api/users")
public class PublicGrowthController {
    private final GrowthPlanService service;

    public PublicGrowthController(GrowthPlanService service) {
        this.service = service;
    }

    @GetMapping("/{uid}/growth")
    public ApiResponse<GrowthDtos.PublicGrowthView> growth(@PathVariable String uid, Authentication authentication) {
        Long viewerId = authentication != null && authentication.getPrincipal() instanceof CurrentUser user ? user.id() : null;
        return ApiResponse.ok(service.publicGrowth(uid, viewerId));
    }
}
