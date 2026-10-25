package com.biecuoguo.web;

import com.biecuoguo.dto.UserDtos;
import com.biecuoguo.security.CurrentUser;
import com.biecuoguo.security.SecurityUtils;
import com.biecuoguo.service.SocialService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class SocialController {
    private final SocialService socialService;

    public SocialController(SocialService socialService) {
        this.socialService = socialService;
    }

    /** 按昵称或 UID 找人，需要登录；?q= 至少两个字。 */
    @GetMapping("/search")
    public ApiResponse<List<UserDtos.FollowUserView>> search(@RequestParam String q, Authentication authentication) {
        CurrentUser user = authentication != null && authentication.getPrincipal() instanceof CurrentUser current ? current : null;
        return ApiResponse.ok(socialService.searchUsers(q, user));
    }

    @PostMapping("/{uid}/follow")
    public ApiResponse<Boolean> follow(@PathVariable String uid) {
        return ApiResponse.ok(socialService.follow(uid, SecurityUtils.currentUser()));
    }

    @DeleteMapping("/{uid}/follow")
    public ApiResponse<Boolean> unfollow(@PathVariable String uid) {
        return ApiResponse.ok(socialService.unfollow(uid, SecurityUtils.currentUser()));
    }

    /** 粉丝列表；匿名可看，登录时额外返回「我是否关注了对方」。 */
    @GetMapping("/{uid}/followers")
    public ApiResponse<List<UserDtos.FollowUserView>> followers(@PathVariable String uid, Authentication authentication) {
        return ApiResponse.ok(socialService.followers(uid, viewerId(authentication)));
    }

    @GetMapping("/{uid}/following")
    public ApiResponse<List<UserDtos.FollowUserView>> following(@PathVariable String uid, Authentication authentication) {
        return ApiResponse.ok(socialService.following(uid, viewerId(authentication)));
    }

    /** 移除粉丝：只能移除自己的粉丝。 */
    @DeleteMapping("/me/followers/{followerUid}")
    public ApiResponse<Boolean> removeFollower(@PathVariable String followerUid) {
        return ApiResponse.ok(socialService.removeFollower(followerUid, SecurityUtils.currentUser()));
    }

    /** 个人主页的「+n 新粉丝」；打开粉丝列表后调用 /seen 清零。 */
    @GetMapping("/me/blocks")
    public ApiResponse<List<UserDtos.BlockedUserView>> myBlocks() {
        return ApiResponse.ok(socialService.myBlocks(SecurityUtils.currentUser()));
    }

    @GetMapping("/me/followers/new-count")
    public ApiResponse<UserDtos.NewFollowersView> newFollowers() {
        return ApiResponse.ok(new UserDtos.NewFollowersView(
                socialService.newFollowerCount(SecurityUtils.currentUser().id())));
    }

    @PostMapping("/me/followers/seen")
    public ApiResponse<Boolean> markFollowersSeen() {
        socialService.markFollowersSeen(SecurityUtils.currentUser().id());
        return ApiResponse.ok(true);
    }

    @PostMapping("/{uid}/block")
    public ApiResponse<UserDtos.BlockStatusView> block(@PathVariable String uid) {
        return ApiResponse.ok(socialService.block(uid, SecurityUtils.currentUser()));
    }

    @DeleteMapping("/{uid}/block")
    public ApiResponse<UserDtos.BlockStatusView> unblock(@PathVariable String uid) {
        return ApiResponse.ok(socialService.unblock(uid, SecurityUtils.currentUser()));
    }

    private Long viewerId(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof CurrentUser user ? user.id() : null;
    }
}
