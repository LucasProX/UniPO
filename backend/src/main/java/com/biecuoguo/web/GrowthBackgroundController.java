package com.biecuoguo.web;

import com.biecuoguo.dto.UserDtos;
import com.biecuoguo.dto.UserProfile;
import com.biecuoguo.security.SecurityUtils;
import com.biecuoguo.service.GrowthBackgroundService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/me/growth-background")
public class GrowthBackgroundController {
    private final GrowthBackgroundService growthBackgroundService;

    public GrowthBackgroundController(GrowthBackgroundService growthBackgroundService) {
        this.growthBackgroundService = growthBackgroundService;
    }

    @PutMapping
    public ApiResponse<UserProfile> update(@Valid @RequestBody UserDtos.GrowthBackgroundRequest request) {
        return ApiResponse.ok(growthBackgroundService.update(SecurityUtils.currentUser().id(), request));
    }
}
