package com.biecuoguo.web;

import com.biecuoguo.dto.NoticeDtos;
import com.biecuoguo.security.CurrentUser;
import com.biecuoguo.service.NoticeService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/notices")
public class NoticeController {
    private final NoticeService noticeService;

    public NoticeController(NoticeService noticeService) {
        this.noticeService = noticeService;
    }

    @GetMapping
    public ApiResponse<List<NoticeDtos.NoticeSummary>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String filter,
            Authentication authentication
    ) {
        return ApiResponse.ok(noticeService.list(new NoticeDtos.NoticeQuery(q, categoryId, filter, "published"), currentUser(authentication), false));
    }

    @GetMapping("/recommended")
    public ApiResponse<List<NoticeDtos.NoticeSummary>> recommended(Authentication authentication) {
        return ApiResponse.ok(noticeService.recommended(currentUser(authentication)));
    }

    @GetMapping("/calendar")
    public ApiResponse<List<NoticeDtos.CalendarDay>> calendar(Authentication authentication) {
        return ApiResponse.ok(noticeService.calendar(currentUser(authentication)));
    }

    @GetMapping("/{id}")
    public ApiResponse<NoticeDtos.NoticeDetail> detail(@PathVariable Long id, Authentication authentication) {
        return ApiResponse.ok(noticeService.detail(id, currentUser(authentication), false));
    }

    @PostMapping("/{id}/feedback")
    public ApiResponse<Boolean> feedback(@PathVariable Long id, @RequestBody NoticeDtos.FeedbackRequest request) {
        return ApiResponse.ok(true);
    }

    private CurrentUser currentUser(Authentication authentication) {
        return authentication == null || !(authentication.getPrincipal() instanceof CurrentUser user) ? null : user;
    }
}
