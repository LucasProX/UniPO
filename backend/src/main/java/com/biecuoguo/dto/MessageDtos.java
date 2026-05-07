package com.biecuoguo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public final class MessageDtos {
    private MessageDtos() {}

    public record ConversationView(
            Long id,
            PostDtos.AuthorView peer,
            String lastMessage,
            Long unreadCount,
            LocalDateTime lastMessageAt,
            LocalDateTime updatedAt
    ) {}

    public record MessageView(
            Long id,
            Long conversationId,
            Long senderId,
            Long receiverId,
            String content,
            Boolean read,
            LocalDateTime createdAt
    ) {}

    public record CreateConversationRequest(@Size(max = 32) String peerUid, Long peerId) {}

    public record SendMessageRequest(@NotBlank @Size(max = 1000) String content) {}
}
