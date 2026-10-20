package com.biecuoguo.web;

import com.biecuoguo.post.api.PostDtos;
import com.biecuoguo.dto.UserDtos;
import com.biecuoguo.security.CurrentUser;
import com.biecuoguo.post.api.PostQueryApi;
import com.biecuoguo.service.UserService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class PublicUserController {
    private final UserService userService;
    private final PostQueryApi postQueryApi;

    public PublicUserController(UserService userService, PostQueryApi postQueryApi) {
        this.userService = userService;
        this.postQueryApi = postQueryApi;
    }

    @GetMapping("/{uid}")
    public ApiResponse<UserDtos.PublicProfile> profile(@PathVariable String uid, Authentication authentication) {
        CurrentUser viewer = currentUser(authentication);
        return ApiResponse.ok(userService.publicProfile(uid, viewer == null ? null : viewer.id()));
    }

    @GetMapping("/{uid}/posts")
    public ApiResponse<List<PostDtos.PostView>> posts(@PathVariable String uid, Authentication authentication) {
        return ApiResponse.ok(postQueryApi.userPostsByUid(uid, currentUser(authentication)));
    }

    @GetMapping("/{uid}/likes")
    public ApiResponse<List<PostDtos.PostView>> likes(@PathVariable String uid, Authentication authentication) {
        return ApiResponse.ok(postQueryApi.likedPostsByUid(uid, currentUser(authentication)));
    }

    @GetMapping("/{uid}/post-favorites")
    public ApiResponse<List<PostDtos.PostView>> postFavorites(@PathVariable String uid, Authentication authentication) {
        return ApiResponse.ok(postQueryApi.favoritePostsByUid(uid, currentUser(authentication)));
    }

    private CurrentUser currentUser(Authentication authentication) {
        return authentication == null || !(authentication.getPrincipal() instanceof CurrentUser user) ? null : user;
    }
}
