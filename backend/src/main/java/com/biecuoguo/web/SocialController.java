package com.biecuoguo.web;

import com.biecuoguo.security.SecurityUtils;
import com.biecuoguo.service.SocialService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class SocialController {
    private final SocialService socialService;

    public SocialController(SocialService socialService) {
        this.socialService = socialService;
    }

    @PostMapping("/{uid}/follow")
    public ApiResponse<Boolean> follow(@PathVariable String uid) {
        return ApiResponse.ok(socialService.follow(uid, SecurityUtils.currentUser()));
    }

    @DeleteMapping("/{uid}/follow")
    public ApiResponse<Boolean> unfollow(@PathVariable String uid) {
        return ApiResponse.ok(socialService.unfollow(uid, SecurityUtils.currentUser()));
    }
}
