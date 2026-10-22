-- Preserve nullable keys for legacy clients; new clients retry one logical message with one stable key.
ALTER TABLE messages
    ADD COLUMN client_message_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD UNIQUE KEY uk_messages_sender_client_intent (sender_id, client_message_id),
    ADD KEY idx_messages_conversation_id (conversation_id, id),
    ADD KEY idx_messages_received_unread_conversation (receiver_id, is_read, conversation_id);

-- This covering index also serves the receiver foreign key; its shorter predecessor is redundant.
ALTER TABLE messages DROP INDEX idx_messages_unread;

-- The generated key keeps empty conversations sortable without depending on the JVM default zone.
ALTER TABLE conversations
    ADD COLUMN activity_at DATETIME GENERATED ALWAYS AS (COALESCE(last_message_at, created_at)) STORED,
    ADD KEY idx_conversations_one_activity (user_one_id, activity_at, id),
    ADD KEY idx_conversations_two_activity (user_two_id, activity_at, id);
