package com.biecuoguo.feedback.internal;

import com.biecuoguo.feedback.api.FeedbackDtos;
import com.biecuoguo.security.CurrentUser;
import com.biecuoguo.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class FeedbackController {
    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @PostMapping("/api/feedback")
    public ApiResponse<Long> submit(@Valid @RequestBody FeedbackDtos.CreateFeedbackRequest request, Authentication authentication) {
        return ApiResponse.ok(feedbackService.submit(request, currentUser(authentication)));
    }

    /** 只有 ADMIN 能看（SecurityConfig 单独收紧）：反馈正文可能带个人信息。 */
    @GetMapping("/api/admin/feedback")
    public ApiResponse<FeedbackDtos.FeedbackPage> list(@RequestParam(required = false) Long beforeId) {
        return ApiResponse.ok(feedbackService.list(beforeId));
    }

    private static CurrentUser currentUser(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof CurrentUser user ? user : null;
    }
}
