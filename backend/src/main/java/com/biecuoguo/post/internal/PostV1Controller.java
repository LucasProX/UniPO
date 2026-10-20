package com.biecuoguo.post.internal;

import com.biecuoguo.post.api.PostDtos;
import com.biecuoguo.security.CurrentUser;
import com.biecuoguo.web.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 只挂在 /api/v1 下的端点。
 *
 * 这里放的是「与 /api 契约不同」的端点：/api/posts 直接返回全量数组，
 * 而 /api/v1/posts/page 返回游标分页。新端点的演进约束见
 * docs/MOBILE-API-PLAN.md 4.1：只做加法，删字段/改语义/改类型才升 v2。
 */
@RestController
@RequestMapping("/api/v1/posts")
public class PostV1Controller {
    private final PostService postService;

    public PostV1Controller(PostService postService) {
        this.postService = postService;
    }

    /**
     * 游标分页列表。
     *
     * limit 默认 20、上限 50；cursor 传上一页返回的 nextCursor。
     * 返回 items / nextCursor / hasMore / limit。
     */
    @GetMapping("/page")
    public ApiResponse<PostDtos.PostPageView> page(@RequestParam(defaultValue = "recommend") String tab,
                                                   @RequestParam(required = false) String cursor,
                                                   @RequestParam(required = false) Integer limit,
                                                   Authentication authentication) {
        CurrentUser currentUser = authentication == null || !(authentication.getPrincipal() instanceof CurrentUser user) ? null : user;
        return ApiResponse.ok(postService.list(tab, currentUser, cursor, limit));
    }
}
