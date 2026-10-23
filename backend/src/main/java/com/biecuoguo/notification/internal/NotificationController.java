package com.biecuoguo.notification.internal;

import com.biecuoguo.notification.api.NotificationDtos;
import com.biecuoguo.security.SecurityUtils;
import com.biecuoguo.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<NotificationDtos.NotificationPage> page(@RequestParam(required = false) String cursor,
                                                               @RequestParam(required = false) Integer limit) {
        Long before = null;
        if (cursor != null && !cursor.isBlank()) {
            if (!cursor.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("通知游标不合法");
            before = Long.parseLong(cursor);
        }
        return ApiResponse.ok(service.page(SecurityUtils.currentUser(), before, limit));
    }

    @GetMapping("/unread-count")
    public ApiResponse<Integer> unread() {
        return ApiResponse.ok(service.unread(SecurityUtils.currentUser()));
    }

    @PostMapping("/read-all")
    public ApiResponse<Integer> readAll() {
        return ApiResponse.ok(service.readAll(SecurityUtils.currentUser()));
    }
}
