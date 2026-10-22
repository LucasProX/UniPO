package com.biecuoguo.message.internal;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/** Opaque traversal position, never authority: every query still checks the current participant. */
final class MessageCursor {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private MessageCursor() {}

    record ConversationPosition(LocalDateTime activityAt, long id) {}
    record MessagePosition(long id, Long throughId) {
        boolean forward() { return throughId != null; }
    }

    static String conversation(long owner, LocalDateTime activityAt, long id) {
        return encode("c1|" + owner + "|" + DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(activityAt) + "|" + id);
    }

    static ConversationPosition conversation(String cursor, long owner) {
        if (cursor == null || cursor.isEmpty()) { return null; }
        try {
            String[] fields = decode(cursor);
            if (fields.length != 4 || !fields[0].equals("c1") || positive(fields[1]) != owner) { throw invalid(); }
            LocalDateTime activityAt = LocalDateTime.parse(fields[2], DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            if (activityAt.getYear() < 1000 || activityAt.getYear() > 9999 || activityAt.getNano() % 1000 != 0) {
                throw invalid();
            }
            return new ConversationPosition(activityAt, positive(fields[3]));
        } catch (RuntimeException malformed) { throw invalid(); }
    }

    static String messages(long owner, long conversationId, long lastMessageId) {
        return encode("m1|" + owner + "|" + conversationId + "|" + lastMessageId);
    }

    static Long messages(String cursor, long owner, long conversationId) {
        MessagePosition position = messagePosition(cursor, owner, conversationId);
        return position == null ? null : position.id();
    }

    static String messagesAfter(long owner, long conversationId, long lastMessageId, long throughId) {
        return encode("m2|" + owner + "|" + conversationId + "|" + lastMessageId + "|" + throughId);
    }

    static MessagePosition messagePosition(String cursor, long owner, long conversationId) {
        if (cursor == null || cursor.isEmpty()) { return null; }
        try {
            String[] fields = decode(cursor);
            boolean forward = fields.length == 5 && fields[0].equals("m2");
            if ((!forward && (fields.length != 4 || !fields[0].equals("m1"))) || positive(fields[1]) != owner
                    || positive(fields[2]) != conversationId) { throw invalid(); }
            long id = positive(fields[3]);
            Long throughId = forward ? positive(fields[4]) : null;
            if (throughId != null && throughId < id) { throw invalid(); }
            return new MessagePosition(id, throughId);
        } catch (RuntimeException malformed) { throw invalid(); }
    }

    private static String encode(String raw) { return ENCODER.encodeToString(raw.getBytes(StandardCharsets.UTF_8)); }

    private static String[] decode(String cursor) {
        if (cursor.length() > 256 || !cursor.matches("[A-Za-z0-9_-]+")) { throw invalid(); }
        byte[] bytes = DECODER.decode(cursor);
        if (!ENCODER.encodeToString(bytes).equals(cursor)) { throw invalid(); }
        return new String(bytes, StandardCharsets.UTF_8).split("\\|", -1);
    }

    private static long positive(String value) {
        if (!value.matches("[1-9][0-9]{0,18}")) { throw invalid(); }
        return Long.parseLong(value);
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("消息游标不合法"); }
}
