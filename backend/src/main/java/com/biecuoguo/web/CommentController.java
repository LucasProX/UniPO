package com.biecuoguo.web;

import com.biecuoguo.dto.CommentDtos;
import com.biecuoguo.security.SecurityUtils;
import com.biecuoguo.service.CommentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class CommentController {
    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    @GetMapping("/api/notices/{id}/comments")
    public ApiResponse<List<CommentDtos.CommentView>> list(@PathVariable Long id, @RequestParam(required = false) String sort) {
        return ApiResponse.ok(commentService.list(id, sort));
    }

    @PostMapping("/api/notices/{id}/comments")
    public ApiResponse<CommentDtos.CommentView> create(@PathVariable Long id, @Valid @RequestBody CommentDtos.CommentRequest request) {
        return ApiResponse.ok(commentService.create(id, request, SecurityUtils.currentUser()));
    }

    @PostMapping("/api/comments/{id}/like")
    public ApiResponse<CommentDtos.CommentView> like(@PathVariable Long id) {
        return ApiResponse.ok(commentService.like(id));
    }

    @DeleteMapping("/api/comments/{id}/like")
    public ApiResponse<CommentDtos.CommentView> unlike(@PathVariable Long id) {
        return ApiResponse.ok(commentService.unlike(id));
    }

    @PostMapping("/api/comments/{id}/report")
    public ApiResponse<Boolean> report(@PathVariable Long id) {
        commentService.report(id);
        return ApiResponse.ok(true);
    }
}
