package com.biecuoguo.message.api;

import com.biecuoguo.post.api.PostDtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

public final class MessageDtos {
    private MessageDtos() {}

    public record ConversationView(
            Long id,
            PostDtos.AuthorView peer,
            String lastMessage,
            Long unreadCount,
            OffsetDateTime lastMessageAt,
            OffsetDateTime updatedAt
    ) {}

    public record MessageView(
            Long id,
            Long conversationId,
            Long senderId,
            Long receiverId,
            String content,
            Boolean read,
            OffsetDateTime createdAt,
            String clientMessageId
    ) {
        /** Existing callers and stored messages have no client intent identifier. */
        public MessageView(Long id, Long conversationId, Long senderId, Long receiverId,
                           String content, Boolean read, OffsetDateTime createdAt) {
            this(id, conversationId, senderId, receiverId, content, read, createdAt, null);
        }
    }

    public record ConversationPage(List<ConversationView> items, String nextCursor, boolean hasMore, int limit) {}

    /** History is newest first; afterId recovery is oldest first within a fixed upper bound. */
    public record MessagePage(List<MessageView> items, String nextCursor, boolean hasMore, int limit) {}

    public record CreateConversationRequest(@Size(max = 32) String peerUid, Long peerId) {}

    public record SendMessageRequest(
            @NotBlank @Size(max = 1000) String content,
            @Size(min = 1, max = 64) @Pattern(regexp = "[A-Za-z0-9._:-]+") String clientMessageId
    ) {
        public SendMessageRequest(String content) { this(content, null); }
    }
}
