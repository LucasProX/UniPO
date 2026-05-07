package com.biecuoguo.web;

import com.biecuoguo.dto.MessageDtos;
import com.biecuoguo.security.SecurityUtils;
import com.biecuoguo.service.MessageService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/messages")
public class MessageController {
    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    @GetMapping("/conversations")
    public ApiResponse<List<MessageDtos.ConversationView>> conversations() {
        return ApiResponse.ok(messageService.conversations(SecurityUtils.currentUser()));
    }

    @PostMapping("/conversations")
    public ApiResponse<MessageDtos.ConversationView> create(@Valid @RequestBody MessageDtos.CreateConversationRequest request) {
        return ApiResponse.ok(messageService.create(request, SecurityUtils.currentUser()));
    }

    @GetMapping("/conversations/{id}/messages")
    public ApiResponse<List<MessageDtos.MessageView>> messages(@PathVariable Long id) {
        return ApiResponse.ok(messageService.messages(id, SecurityUtils.currentUser()));
    }

    @PostMapping("/conversations/{id}/messages")
    public ApiResponse<MessageDtos.MessageView> send(@PathVariable Long id, @Valid @RequestBody MessageDtos.SendMessageRequest request) {
        return ApiResponse.ok(messageService.send(id, request, SecurityUtils.currentUser()));
    }

    @GetMapping("/unread-count")
    public ApiResponse<Long> unreadCount() {
        return ApiResponse.ok(messageService.unreadCount(SecurityUtils.currentUser()));
    }
}
