package com.biecuoguo.web;

import com.biecuoguo.dto.PostDtos;
import com.biecuoguo.dto.UserDtos;
import com.biecuoguo.security.CurrentUser;
import com.biecuoguo.service.PostService;
import com.biecuoguo.service.UserService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class PublicUserController {
    private final UserService userService;
    private final PostService postService;

    public PublicUserController(UserService userService, PostService postService) {
        this.userService = userService;
        this.postService = postService;
    }

    @GetMapping("/{uid}")
    public ApiResponse<UserDtos.PublicProfile> profile(@PathVariable String uid, Authentication authentication) {
        CurrentUser viewer = currentUser(authentication);
        return ApiResponse.ok(userService.publicProfile(uid, viewer == null ? null : viewer.id()));
    }

    @GetMapping("/{uid}/posts")
    public ApiResponse<List<PostDtos.PostView>> posts(@PathVariable String uid, Authentication authentication) {
        return ApiResponse.ok(postService.userPostsByUid(uid, currentUser(authentication)));
    }

    private CurrentUser currentUser(Authentication authentication) {
        return authentication == null || !(authentication.getPrincipal() instanceof CurrentUser user) ? null : user;
    }
}
