package com.biecuoguo.post.internal;

import com.biecuoguo.web.ApiResponse;

import com.biecuoguo.post.api.PostDtos;
import com.biecuoguo.security.CurrentUser;
import com.biecuoguo.security.SecurityUtils;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/posts")
public class PostController {
    private final PostService postService;

    public PostController(PostService postService) {
        this.postService = postService;
    }

    @GetMapping
    public ApiResponse<List<PostDtos.PostView>> list(@RequestParam(defaultValue = "recommend") String tab, Authentication authentication) {
        return ApiResponse.ok(postService.list(tab, currentUser(authentication)));
    }

    @GetMapping("/{id:\\d+}")
    public ApiResponse<PostDtos.PostView> detail(@PathVariable long id, Authentication authentication) {
        return ApiResponse.ok(postService.detail(id, currentUser(authentication)));
    }

    /** 关联到某个活动或主线任务的帖子：?activityId= 或 ?taskId= 二选一。 */
    @GetMapping("/linked")
    public ApiResponse<java.util.List<PostDtos.PostView>> linked(@RequestParam(required = false) Long activityId,
                                                                @RequestParam(required = false) Long taskId,
                                                                @RequestParam(defaultValue = "20") int limit,
                                                                Authentication authentication) {
        if ((activityId == null) == (taskId == null)) {
            throw new IllegalArgumentException("activityId 与 taskId 需要且只能给一个");
        }
        return ApiResponse.ok(activityId != null
                ? postService.linkedPosts("activity", activityId, currentUser(authentication), limit)
                : postService.linkedPosts("task", taskId, currentUser(authentication), limit));
    }

    @GetMapping("/boards")
    public ApiResponse<List<PostDtos.BoardView>> boards(Authentication authentication) {
        return ApiResponse.ok(postService.boards(currentUser(authentication)));
    }

    @GetMapping("/hot-today")
    public ApiResponse<PostDtos.PostView> hotToday(Authentication authentication) {
        return ApiResponse.ok(postService.hotToday(currentUser(authentication)));
    }

    @GetMapping("/dont-miss")
    public ApiResponse<List<PostDtos.PostView>> dontMiss(Authentication authentication) {
        return ApiResponse.ok(postService.dontMiss(currentUser(authentication)));
    }

    @GetMapping("/interaction-notices")
    public ApiResponse<List<PostDtos.InteractionNoticeView>> interactionNotices() {
        return ApiResponse.ok(postService.interactionNotices(SecurityUtils.currentUser()));
    }

    @PostMapping
    public ApiResponse<PostDtos.PostView> create(@Valid @RequestBody PostDtos.CreatePostRequest request) {
        return ApiResponse.ok(postService.create(request, SecurityUtils.currentUser()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Boolean> delete(@PathVariable Long id) {
        return ApiResponse.ok(postService.deleteOwnPost(id, SecurityUtils.currentUser()));
    }

    @GetMapping("/{id}/comments")
    public ApiResponse<List<PostDtos.CommentView>> comments(@PathVariable Long id, Authentication authentication) {
        return ApiResponse.ok(postService.comments(id, currentUser(authentication)));
    }

    @PostMapping("/{id}/comments")
    public ApiResponse<PostDtos.CommentView> comment(@PathVariable Long id, @Valid @RequestBody PostDtos.CreateCommentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestKey) {
        return ApiResponse.ok(postService.comment(id, request, SecurityUtils.currentUser(), requestKey));
    }

    @PostMapping("/comments/{id}/like")
    public ApiResponse<Boolean> likeComment(@PathVariable Long id) {
        return ApiResponse.ok(postService.likeComment(id, SecurityUtils.currentUser()));
    }

    @DeleteMapping("/comments/{id}/like")
    public ApiResponse<Boolean> unlikeComment(@PathVariable Long id) {
        return ApiResponse.ok(postService.unlikeComment(id, SecurityUtils.currentUser()));
    }

    @PostMapping("/{id}/like")
    public ApiResponse<Boolean> like(@PathVariable Long id) {
        return ApiResponse.ok(postService.like(id, SecurityUtils.currentUser()));
    }

    @DeleteMapping("/{id}/like")
    public ApiResponse<Boolean> unlike(@PathVariable Long id) {
        return ApiResponse.ok(postService.unlike(id, SecurityUtils.currentUser()));
    }

    @PostMapping("/{id}/favorite")
    public ApiResponse<Boolean> favorite(@PathVariable Long id) {
        return ApiResponse.ok(postService.favorite(id, SecurityUtils.currentUser()));
    }

    @DeleteMapping("/{id}/favorite")
    public ApiResponse<Boolean> unfavorite(@PathVariable Long id) {
        return ApiResponse.ok(postService.unfavorite(id, SecurityUtils.currentUser()));
    }

    @PostMapping("/{id}/share")
    public ApiResponse<PostDtos.ShareResponse> share(@PathVariable Long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestKey) {
        return ApiResponse.ok(postService.share(id, SecurityUtils.currentUser(), requestKey));
    }

    private CurrentUser currentUser(Authentication authentication) {
        return authentication == null || !(authentication.getPrincipal() instanceof CurrentUser user) ? null : user;
    }
}
