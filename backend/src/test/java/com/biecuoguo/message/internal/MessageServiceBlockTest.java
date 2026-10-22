package com.biecuoguo.message.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.biecuoguo.domain.User;
import com.biecuoguo.mapper.UserMapper;
import com.biecuoguo.message.api.MessageDtos;
import com.biecuoguo.security.CurrentUser;
import com.biecuoguo.service.PresenceService;
import com.biecuoguo.social.api.BlockQueryApi;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class MessageServiceBlockTest {

    private static final CurrentUser VIEWER = new CurrentUser(10L, "viewer", "USER");
    private static final long PEER_ID = 20L;

    private final ConversationMapper conversations = mock(ConversationMapper.class);
    private final MessageMapper messages = mock(MessageMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final PresenceService presence = mock(PresenceService.class);
    private final MessageOutboxStore outbox = mock(MessageOutboxStore.class);
    private final BlockQueryApi blocks = mock(BlockQueryApi.class);
    private MessageService service;

    @BeforeAll
    static void initializeLambdaMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "message-block-tests"),
                Conversation.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "message-block-tests-message"),
                Message.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "message-block-tests-user"),
                User.class);
    }

    @BeforeEach
    void createService() {
        service = new MessageService(conversations, messages, users, presence, outbox, blocks);
        when(messages.insert(any(Message.class))).thenAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            message.setId(99L);
            return 1;
        });
    }

    @Test
    void createRejectsBlockedPeerBeforeLookingUpOrCreatingConversation() {
        User peer = new User();
        peer.setId(PEER_ID);
        when(users.selectById(PEER_ID)).thenReturn(peer);
        when(blocks.isBlockedInLockedPair(VIEWER.id(), PEER_ID)).thenReturn(true);

        assertThrows(NoSuchElementException.class, () -> service.create(
                new MessageDtos.CreateConversationRequest(null, PEER_ID), VIEWER));

        verifyNoInteractions(conversations, messages, outbox);
    }

    @Test
    void messagesRejectsBlockedPeerBeforeReadingOrMarkingAnythingRead() {
        when(conversations.selectById(5L)).thenReturn(conversation(5L, VIEWER.id(), PEER_ID));
        when(blocks.isBlockedInLockedPair(VIEWER.id(), PEER_ID)).thenReturn(true);

        assertThrows(NoSuchElementException.class, () -> service.messages(5L, VIEWER));

        verifyNoInteractions(messages, outbox);
    }

    @Test
    void sendRejectsBlockedPeerBeforeWritingOrPublishing() {
        when(conversations.selectById(5L)).thenReturn(conversation(5L, VIEWER.id(), PEER_ID));
        when(blocks.isBlockedInLockedPair(VIEWER.id(), PEER_ID)).thenReturn(true);

        assertThrows(NoSuchElementException.class, () -> service.send(5L,
                new MessageDtos.SendMessageRequest("hello"), VIEWER));

        verify(messages, never()).insert(any(Message.class));
        verify(conversations, never()).updateById(any(Conversation.class));
        verifyNoInteractions(outbox);
    }

    @Test
    void sendSavesOneDurableIntentForItsNewMessage() {
        when(conversations.selectById(5L)).thenReturn(conversation(5L, VIEWER.id(), PEER_ID));
        MessageDtos.MessageView result = service.send(5L, new MessageDtos.SendMessageRequest("hello"), VIEWER);

        assertEquals(99L, result.id());
        verify(outbox).enqueue(99L);
        verify(blocks).isBlockedInLockedPair(VIEWER.id(), PEER_ID);
        verify(blocks, never()).isBlockedEitherWay(any(), any());
    }

    @Test
    void outboxFailurePropagatesToTheMessageTransaction() {
        when(conversations.selectById(5L)).thenReturn(conversation(5L, VIEWER.id(), PEER_ID));
        doThrow(new IllegalStateException("outbox unavailable")).when(outbox).enqueue(99L);

        assertThrows(IllegalStateException.class, () -> service.send(5L,
                new MessageDtos.SendMessageRequest("hello"), VIEWER));
        verify(outbox).enqueue(99L);
    }

    @Test
    void conversationsAndUnreadCountFilterPeersBlockedInEitherDirection() {
        when(blocks.blockedUserIds(VIEWER.id())).thenReturn(Set.of(PEER_ID));
        AtomicReference<String> sql = new AtomicReference<>();
        when(conversations.selectList(any())).thenAnswer(invocation -> {
            LambdaQueryWrapper<Conversation> query = invocation.getArgument(0);
            sql.set(query.getSqlSegment());
            return List.of();
        });

        assertTrue(service.conversations(VIEWER).isEmpty());
        assertEquals(0L, service.unreadCount(VIEWER));

        String predicate = sql.get().toLowerCase();
        assertTrue(predicate.contains("user_one_id") && predicate.contains("user_two_id")
                && count(predicate, "not in") == 2, "Both participant columns must exclude the blocked peer");
        verifyNoInteractions(messages, outbox);
    }

    @Test
    void conversationsHideAPeerBlockedWhilePreviewsAreBeingLoaded() {
        setupVisibleCandidates();
        AtomicReference<Set<Long>> committedBlocks = new AtomicReference<>(Set.of());
        when(blocks.blockedUserIds(VIEWER.id())).thenAnswer(invocation -> committedBlocks.get());
        when(messages.selectList(any())).thenAnswer(invocation -> {
            committedBlocks.set(Set.of(PEER_ID));
            return List.of(message(5L, "private preview"), message(6L, "visible preview"));
        });

        List<MessageDtos.ConversationView> result = service.conversations(VIEWER);

        assertEquals(1, result.size());
        assertEquals(6L, result.getFirst().id());
        assertEquals("visible preview", result.getFirst().lastMessage());
        assertEquals(2L, result.getFirst().unreadCount());
        verify(blocks, times(2)).blockedUserIds(VIEWER.id());
        verify(messages).selectUnreadCounts(VIEWER.id(), List.of(5L, 6L));
        verify(blocks, never()).isBlockedEitherWay(any(), any());
    }

    @Test
    void unreadCountDropsAPeerBlockedDuringTheAggregateQuery() {
        setupVisibleCandidates();
        AtomicReference<Set<Long>> committedBlocks = new AtomicReference<>(Set.of());
        when(blocks.blockedUserIds(VIEWER.id())).thenAnswer(invocation -> committedBlocks.get());
        when(messages.selectUnreadCounts(VIEWER.id(), List.of(5L, 6L))).thenAnswer(invocation -> {
            committedBlocks.set(Set.of(PEER_ID));
            return List.of(new MessageMapper.UnreadCount(5L, 3L), new MessageMapper.UnreadCount(6L, 2L));
        });

        assertEquals(2L, service.unreadCount(VIEWER));

        verify(blocks, times(2)).blockedUserIds(VIEWER.id());
        verify(messages, never()).selectList(any());
        verify(blocks, never()).isBlockedEitherWay(any(), any());
    }

    @Test
    void finalRelationshipFailureDoesNotReturnPreparedPreviewsOrCounts() {
        setupVisibleCandidates();
        when(blocks.blockedUserIds(VIEWER.id())).thenReturn(Set.of())
                .thenThrow(new IllegalStateException("relationship store unavailable"))
                .thenReturn(Set.of())
                .thenThrow(new IllegalStateException("relationship store unavailable"));

        assertThrows(IllegalStateException.class, () -> service.conversations(VIEWER));
        assertThrows(IllegalStateException.class, () -> service.unreadCount(VIEWER));
    }

    @Test
    void relationshipLookupFailureFailsClosedBeforeMessageReadOrWrite() {
        when(conversations.selectById(5L)).thenReturn(conversation(5L, VIEWER.id(), PEER_ID));
        when(blocks.isBlockedInLockedPair(VIEWER.id(), PEER_ID))
                .thenThrow(new IllegalStateException("relationship store unavailable"));

        assertThrows(IllegalStateException.class, () -> service.messages(5L, VIEWER));
        assertThrows(IllegalStateException.class, () -> service.send(5L,
                new MessageDtos.SendMessageRequest("hello"), VIEWER));

        verifyNoInteractions(messages, outbox);
    }

    @Test
    void blockedUserListLookupFailureFailsClosedForConversationAndUnreadQueries() {
        when(blocks.blockedUserIds(VIEWER.id())).thenThrow(new IllegalStateException("relationship store unavailable"));

        assertThrows(IllegalStateException.class, () -> service.conversations(VIEWER));
        assertThrows(IllegalStateException.class, () -> service.unreadCount(VIEWER));

        verifyNoInteractions(conversations, messages, outbox);
    }

    private void setupVisibleCandidates() {
        // Cover both positions of the current user in the canonical conversation pair.
        when(conversations.selectList(any())).thenReturn(List.of(
                conversation(5L, VIEWER.id(), PEER_ID), conversation(6L, 5L, VIEWER.id())));
        User firstPeer = new User();
        firstPeer.setId(PEER_ID);
        User secondPeer = new User();
        secondPeer.setId(5L);
        when(users.selectList(any())).thenReturn(List.of(firstPeer, secondPeer));
        when(messages.selectList(any())).thenReturn(List.of(message(5L, "private preview"), message(6L, "visible preview")));
        when(messages.selectUnreadCounts(VIEWER.id(), List.of(5L, 6L)))
                .thenReturn(List.of(new MessageMapper.UnreadCount(5L, 3L), new MessageMapper.UnreadCount(6L, 2L)));
    }

    private static Message message(long conversationId, String content) {
        Message message = new Message();
        message.setConversationId(conversationId);
        message.setContent(content);
        return message;
    }

    private static Conversation conversation(long id, Long firstUserId, Long secondUserId) {
        Conversation conversation = new Conversation();
        conversation.setId(id);
        conversation.setUserOneId(firstUserId);
        conversation.setUserTwoId(secondUserId);
        return conversation;
    }

    private static int count(String value, String needle) {
        return (value.length() - value.replace(needle, "").length()) / needle.length();
    }
}
