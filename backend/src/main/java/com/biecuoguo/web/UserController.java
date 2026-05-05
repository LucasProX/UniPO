package com.biecuoguo.web;

import com.biecuoguo.dto.UserDtos;
import com.biecuoguo.dto.UserProfile;
import com.biecuoguo.dto.PostDtos;
import com.biecuoguo.security.SecurityUtils;
import com.biecuoguo.service.PostService;
import com.biecuoguo.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users/me")
public class UserController {
    private final UserService userService;
    private final PostService postService;

    public UserController(UserService userService, PostService postService) {
        this.userService = userService;
        this.postService = postService;
    }

    @GetMapping
    public ApiResponse<UserProfile> me() {
        return ApiResponse.ok(userService.profile(SecurityUtils.currentUser().id()));
    }

    @PutMapping
    public ApiResponse<UserProfile> updateProfile(@Valid @RequestBody UserDtos.UpdateProfileRequest request) {
        return ApiResponse.ok(userService.updateProfile(SecurityUtils.currentUser().id(), request));
    }

    @PutMapping("/preferences")
    public ApiResponse<UserProfile> updatePreferences(@RequestBody UserDtos.UpdatePreferencesRequest request) {
        return ApiResponse.ok(userService.updatePreferences(SecurityUtils.currentUser().id(), request));
    }

    @GetMapping("/stats")
    public ApiResponse<UserDtos.UserStats> stats() {
        return ApiResponse.ok(userService.stats(SecurityUtils.currentUser().id()));
    }

    @GetMapping("/check-in")
    public ApiResponse<UserDtos.CheckInView> checkInStatus() {
        return ApiResponse.ok(userService.checkInStatus(SecurityUtils.currentUser().id()));
    }

    @PostMapping("/check-in")
    public ApiResponse<UserDtos.CheckInView> checkIn() {
        return ApiResponse.ok(userService.checkIn(SecurityUtils.currentUser().id()));
    }

    @GetMapping("/posts")
    public ApiResponse<java.util.List<PostDtos.PostView>> posts() {
        return ApiResponse.ok(postService.userPosts(SecurityUtils.currentUser().id(), SecurityUtils.currentUser()));
    }

    @GetMapping("/likes")
    public ApiResponse<java.util.List<PostDtos.PostView>> likes() {
        return ApiResponse.ok(postService.likedPosts(SecurityUtils.currentUser()));
    }

    @GetMapping("/post-favorites")
    public ApiResponse<java.util.List<PostDtos.PostView>> postFavorites() {
        return ApiResponse.ok(postService.favoritePosts(SecurityUtils.currentUser()));
    }
}
