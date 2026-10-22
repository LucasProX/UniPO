package com.biecuoguo.message.internal;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MessageCursorTest {
    @Test
    void conversationPositionPreservesTheCompleteOrderingKeyAndOwner() {
        LocalDateTime activity = LocalDateTime.of(2026, 10, 22, 19, 20, 5);
        String cursor = MessageCursor.conversation(17, activity, 103);
        assertEquals(new MessageCursor.ConversationPosition(activity, 103), MessageCursor.conversation(cursor, 17));
        assertThrows(IllegalArgumentException.class, () -> MessageCursor.conversation(cursor, 18));
    }

    @Test
    void messagePositionIsBoundToOwnerConversationAndType() {
        String cursor = MessageCursor.messages(17, 9, 103);
        assertEquals(103L, MessageCursor.messages(cursor, 17, 9));
        assertThrows(IllegalArgumentException.class, () -> MessageCursor.messages(cursor, 18, 9));
        assertThrows(IllegalArgumentException.class, () -> MessageCursor.messages(cursor, 17, 10));
        assertThrows(IllegalArgumentException.class, () -> MessageCursor.conversation(cursor, 17));
    }

    @Test
    void forwardCursorKeepsItsUpperBoundAndCannotBeReusedForAnotherOwnerOrThread() {
        String cursor = MessageCursor.messagesAfter(17, 9, 103, 200);
        assertEquals(new MessageCursor.MessagePosition(103, 200L), MessageCursor.messagePosition(cursor, 17, 9));
        assertThrows(IllegalArgumentException.class, () -> MessageCursor.messagePosition(cursor, 18, 9));
        assertThrows(IllegalArgumentException.class, () -> MessageCursor.messagePosition(cursor, 17, 10));
        assertThrows(IllegalArgumentException.class,
                () -> MessageCursor.messagePosition(encoded("m2|17|9|200|103"), 17, 9));
    }

    @Test
    void emptyPositionStartsTheListAndMalformedOrOutOfRangeValuesFailClosed() {
        assertNull(MessageCursor.conversation(null, 17));
        assertNull(MessageCursor.messages("", 17, 9));
        for (String cursor : new String[]{" ", "not/base64", "a".repeat(257), encoded("m1|17|9|0"),
                encoded("m1|17|9|9223372036854775808"), encoded("m1|17|9|1|extra")}) {
            assertThrows(IllegalArgumentException.class, () -> MessageCursor.messages(cursor, 17, 9));
        }
        assertThrows(IllegalArgumentException.class,
                () -> MessageCursor.conversation(encoded("c1|17|+12026-10-22T19:00:00|1"), 17));
        assertThrows(IllegalArgumentException.class,
                () -> MessageCursor.conversation(encoded("c1|17|2026-10-22T19:00:00.000000001|1"), 17));
    }

    @Test
    void paddedOrCorruptedEncodingsAreNotSilentlyAcceptedAsTheSameCursor() {
        String cursor = MessageCursor.messages(17, 9, 103);
        assertThrows(IllegalArgumentException.class, () -> MessageCursor.messages(cursor + "=", 17, 9));
        assertThrows(IllegalArgumentException.class, () -> MessageCursor.messages(encoded("m1|017|9|103"), 17, 9));
    }

    private static String encoded(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
