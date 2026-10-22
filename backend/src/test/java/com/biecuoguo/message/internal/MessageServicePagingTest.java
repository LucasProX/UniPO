package com.biecuoguo.message.internal;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.biecuoguo.domain.User;
import com.biecuoguo.error.StateConflictException;
import com.biecuoguo.mapper.UserMapper;
import com.biecuoguo.message.api.MessageDtos;
import com.biecuoguo.security.CurrentUser;
import com.biecuoguo.service.PresenceService;
import com.biecuoguo.social.api.BlockQueryApi;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MessageServicePagingTest {
    private static final CurrentUser VIEWER = new CurrentUser(17L, "fixture", "USER");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 22, 19, 0);
    private final ConversationMapper conversations = mock(ConversationMapper.class);
    private final MessageMapper messages = mock(MessageMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final BlockQueryApi blocks = mock(BlockQueryApi.class);
    private final MessageOutboxStore outbox = mock(MessageOutboxStore.class);
    private final PresenceService presence = mock(PresenceService.class);
    private final MessageService service = new MessageService(conversations, messages, users,
            presence, outbox, blocks);

    @BeforeAll
    static void metadata() {
        for (Class<?> type : List.of(Conversation.class, Message.class, User.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), type.getSimpleName()), type);
        }
    }

    @BeforeEach
    void knownConversation() {
        when(conversations.selectById(5L)).thenReturn(conversation(5L, 20L));
    }

    @Test
    void aBlockDuringHydrationCanEmptyAPageWithoutLosingItsRawCursor() {
        when(blocks.blockedUserIds(17L)).thenReturn(Set.of()).thenReturn(Set.of(20L, 21L));
        when(conversations.selectPageRows(17L, Set.of(), null, null, 3))
                .thenReturn(List.of(conversation(5, 20), conversation(4, 21), conversation(3, 22)));
        when(messages.selectLatestForConversations(List.of(5L, 4L))).thenReturn(List.of());
        when(messages.selectUnreadCounts(17L, List.of(5L, 4L))).thenReturn(List.of());
        when(users.selectList(any())).thenReturn(List.of());

        MessageDtos.ConversationPage page = service.conversationPage(null, 2, VIEWER);

        assertTrue(page.items().isEmpty());
        assertTrue(page.hasMore());
        assertEquals(new MessageCursor.ConversationPosition(NOW, 4), MessageCursor.conversation(page.nextCursor(), 17));
        verify(messages).selectLatestForConversations(List.of(5L, 4L));
        verify(messages, never()).selectList(any());
    }

    @Test
    void conversationHydrationUsesOnePresenceBatchWithoutPerPeerNetworkReads() {
        when(conversations.selectList(any())).thenReturn(List.of(conversation(5, 20), conversation(4, 21)));
        User first = new User();
        first.setId(20L);
        User second = new User();
        second.setId(21L);
        when(users.selectList(any())).thenReturn(List.of(first, second));
        when(presence.batchOnlineIds(Set.of(20L, 21L))).thenReturn(Set.of(21L));

        var result = service.conversations(VIEWER);

        assertFalse(result.get(0).peer().online());
        assertTrue(result.get(1).peer().online());
        verify(presence).batchOnlineIds(Set.of(20L, 21L));
        verify(presence, never()).isOnline(any());
    }

    @Test
    void historyOnlyMarksTheReturnedIncomingIdsRatherThanTheLookaheadOrOutgoingRows() {
        Message received = message(30, 5, "received", null);
        Message sent = message(29, 5, "sent", null);
        sent.setReceiverId(20L);
        Message lookahead = message(28, 5, "older", null);
        when(messages.selectPageRows(5L, null, 3)).thenReturn(List.of(received, sent, lookahead));
        AtomicReference<LambdaUpdateWrapper<Message>> mark = new AtomicReference<>();
        when(messages.update(eq(null), any())).thenAnswer(invocation -> {
            mark.set(invocation.getArgument(1));
            return 1;
        });

        MessageDtos.MessagePage page = service.messagePage(5L, null, 2, VIEWER);

        assertEquals(List.of(30L, 29L), page.items().stream().map(MessageDtos.MessageView::id).toList());
        assertTrue(page.items().getFirst().read());
        assertFalse(sent.getReadFlag());
        assertFalse(lookahead.getReadFlag());
        mark.get().getSqlSegment();
        assertTrue(mark.get().getParamNameValuePairs().containsValue(30L));
        assertFalse(mark.get().getParamNameValuePairs().containsValue(29L));
        assertFalse(mark.get().getParamNameValuePairs().containsValue(28L));
        assertEquals(29L, MessageCursor.messages(page.nextCursor(), 17, 5));
    }

    @Test
    void sameIntentReplaysTheCurrentOriginalWithoutAnySecondWrite() {
        Message original = message(99, 5, "hello", "same-intent");
        original.setSenderId(17L);
        original.setReceiverId(20L);
        original.setReadFlag(true);
        when(messages.selectClientIntent(17, "same-intent")).thenReturn(original);
        when(messages.selectClientIntentCurrent(17, "same-intent")).thenReturn(original);

        var result = service.send(5L, new MessageDtos.SendMessageRequest("  hello  ", "same-intent"), VIEWER);

        assertEquals(99L, result.id());
        assertEquals("same-intent", result.clientMessageId());
        assertTrue(result.read());
        verify(messages, never()).insert(any(Message.class));
        verify(conversations, never()).update(any(), any());
        verifyNoInteractions(outbox);
    }

    @Test
    void aGlobalIntentCannotBeReusedForAnotherBodyOrConversation() {
        Message original = message(99, 5, "original", "same-intent");
        original.setSenderId(17L);
        original.setReceiverId(20L);
        when(messages.selectClientIntent(17, "same-intent")).thenReturn(original);
        when(messages.selectClientIntentCurrent(17, "same-intent")).thenReturn(original);
        assertThrows(StateConflictException.class, () -> service.send(5L,
                new MessageDtos.SendMessageRequest("changed", "same-intent"), VIEWER));
        original.setConversationId(6L);
        assertThrows(StateConflictException.class, () -> service.send(5L,
                new MessageDtos.SendMessageRequest("original", "same-intent"), VIEWER));
        verify(messages, never()).insert(any(Message.class));
        verifyNoInteractions(outbox);
    }

    @Test
    void aConcurrentUniqueKeyWinnerIsRecheckedBeforeTheTransactionBoundary() {
        Message original = message(99, 5, "hello", "race-intent");
        original.setSenderId(17L);
        original.setReceiverId(20L);
        when(messages.insert(any(Message.class))).thenThrow(new DuplicateKeyException("synthetic concurrent intent"));
        when(messages.selectClientIntentCurrent(17, "race-intent")).thenReturn(original);

        var result = service.send(5L, new MessageDtos.SendMessageRequest("hello", "race-intent"), VIEWER);

        assertEquals(99L, result.id());
        assertEquals("race-intent", result.clientMessageId());
        verify(conversations, never()).update(any(), any());
        verifyNoInteractions(outbox);
    }

    @Test
    void invalidLimitsAndCrossOwnerCursorsFailBeforePagingQueries() {
        assertThrows(IllegalArgumentException.class, () -> service.conversationPage(null, 0, VIEWER));
        assertThrows(IllegalArgumentException.class, () -> service.conversationPage(
                MessageCursor.conversation(18, NOW, 5), 2, VIEWER));
        assertThrows(IllegalArgumentException.class, () -> service.messagePage(5L,
                MessageCursor.messages(17, 6, 99), 2, VIEWER));
        verify(conversations, never()).selectPageRows(anyLong(), any(), any(), any(), anyInt());
        verify(messages, never()).selectPageRows(anyLong(), any(), anyInt());
        verifyNoInteractions(blocks, outbox);
    }

    @Test
    void exactConversationLookupUsesOnlyTheRequestedLatestPreview() {
        User peer = new User();
        peer.setId(20L);
        when(users.selectById(20L)).thenReturn(peer);
        when(messages.selectLatestForConversations(List.of(5L))).thenReturn(List.of(message(99, 5, "latest", null)));
        when(messages.selectCount(any())).thenReturn(2L);

        var result = service.conversation(5L, VIEWER);

        assertEquals(5L, result.id());
        assertEquals("latest", result.lastMessage());
        assertEquals(2L, result.unreadCount());
        verify(messages, never()).selectList(any());
    }

    private static Conversation conversation(long id, long peerId) {
        Conversation row = new Conversation();
        row.setId(id);
        row.setUserOneId(17L);
        row.setUserTwoId(peerId);
        row.setCreatedAt(NOW);
        row.setLastMessageAt(NOW);
        row.setActivityAt(NOW);
        row.setUpdatedAt(NOW);
        return row;
    }

    private static Message message(long id, long conversationId, String content, String intent) {
        Message row = new Message();
        row.setId(id);
        row.setConversationId(conversationId);
        row.setSenderId(20L);
        row.setReceiverId(17L);
        row.setContent(content);
        row.setClientMessageId(intent);
        row.setCreatedAt(NOW);
        row.setReadFlag(false);
        return row;
    }
}
