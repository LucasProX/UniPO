package com.biecuoguo.message.internal;

import com.biecuoguo.web.ApiResponse;

import com.biecuoguo.message.api.MessageDtos;
import com.biecuoguo.security.SecurityUtils;
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

    @GetMapping("/conversations/page")
    public ApiResponse<MessageDtos.ConversationPage> conversationPage(
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(messageService.conversationPage(cursor, limit, SecurityUtils.currentUser()));
    }

    @GetMapping("/conversations/{id}")
    public ApiResponse<MessageDtos.ConversationView> conversation(@PathVariable Long id) {
        return ApiResponse.ok(messageService.conversation(id, SecurityUtils.currentUser()));
    }

    @PostMapping("/conversations")
    public ApiResponse<MessageDtos.ConversationView> create(@Valid @RequestBody MessageDtos.CreateConversationRequest request) {
        return ApiResponse.ok(messageService.create(request, SecurityUtils.currentUser()));
    }

    @GetMapping("/conversations/{id}/messages")
    public ApiResponse<List<MessageDtos.MessageView>> messages(@PathVariable Long id) {
        return ApiResponse.ok(messageService.messages(id, SecurityUtils.currentUser()));
    }

    @GetMapping("/conversations/{id}/messages/page")
    public ApiResponse<MessageDtos.MessagePage> messagePage(@PathVariable Long id,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Long afterId,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(messageService.messagePage(id, cursor, afterId, limit, SecurityUtils.currentUser()));
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
