package com.biecuoguo.message.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.biecuoguo.domain.User;
import com.biecuoguo.mapper.UserMapper;
import com.biecuoguo.message.api.MessageDtos;
import com.biecuoguo.push.api.PushService;
import com.biecuoguo.realtime.RealtimeHub;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MessageServiceBlockTest {

    private static final CurrentUser VIEWER = new CurrentUser(10L, "viewer", "USER");
    private static final long PEER_ID = 20L;

    private final ConversationMapper conversations = mock(ConversationMapper.class);
    private final MessageMapper messages = mock(MessageMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final PresenceService presence = mock(PresenceService.class);
    private final RealtimeHub realtime = mock(RealtimeHub.class);
    private final PushService push = mock(PushService.class);
    private final BlockQueryApi blocks = mock(BlockQueryApi.class);
    private MessageService service;

    @BeforeAll
    static void initializeLambdaMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "message-block-tests"),
                Conversation.class);
    }

    @BeforeEach
    void createService() {
        service = new MessageService(conversations, messages, users, presence, realtime, push, blocks);
    }

    @Test
    void createRejectsBlockedPeerBeforeLookingUpOrCreatingConversation() {
        User peer = new User();
        peer.setId(PEER_ID);
        when(users.selectById(PEER_ID)).thenReturn(peer);
        when(blocks.isBlockedEitherWay(VIEWER.id(), PEER_ID)).thenReturn(true);

        assertThrows(NoSuchElementException.class, () -> service.create(
                new MessageDtos.CreateConversationRequest(null, PEER_ID), VIEWER));

        verifyNoInteractions(conversations, messages, realtime, push);
    }

    @Test
    void messagesRejectsBlockedPeerBeforeReadingOrMarkingAnythingRead() {
        when(conversations.selectById(5L)).thenReturn(conversation(5L, VIEWER.id(), PEER_ID));
        when(blocks.isBlockedEitherWay(VIEWER.id(), PEER_ID)).thenReturn(true);

        assertThrows(NoSuchElementException.class, () -> service.messages(5L, VIEWER));

        verifyNoInteractions(messages, realtime, push);
    }

    @Test
    void sendRejectsBlockedPeerBeforeWritingOrPublishing() {
        when(conversations.selectById(5L)).thenReturn(conversation(5L, VIEWER.id(), PEER_ID));
        when(blocks.isBlockedEitherWay(VIEWER.id(), PEER_ID)).thenReturn(true);

        assertThrows(NoSuchElementException.class, () -> service.send(5L,
                new MessageDtos.SendMessageRequest("hello"), VIEWER));

        verify(messages, never()).insert(any(Message.class));
        verify(conversations, never()).updateById(any(Conversation.class));
        verifyNoInteractions(realtime, push);
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
        verifyNoInteractions(messages, realtime, push);
    }

    @Test
    void relationshipLookupFailureFailsClosedBeforeMessageReadOrWrite() {
        when(conversations.selectById(5L)).thenReturn(conversation(5L, VIEWER.id(), PEER_ID));
        when(blocks.isBlockedEitherWay(VIEWER.id(), PEER_ID))
                .thenThrow(new IllegalStateException("relationship store unavailable"));

        assertThrows(IllegalStateException.class, () -> service.messages(5L, VIEWER));
        assertThrows(IllegalStateException.class, () -> service.send(5L,
                new MessageDtos.SendMessageRequest("hello"), VIEWER));

        verifyNoInteractions(messages, realtime, push);
    }

    @Test
    void blockedUserListLookupFailureFailsClosedForConversationAndUnreadQueries() {
        when(blocks.blockedUserIds(VIEWER.id())).thenThrow(new IllegalStateException("relationship store unavailable"));

        assertThrows(IllegalStateException.class, () -> service.conversations(VIEWER));
        assertThrows(IllegalStateException.class, () -> service.unreadCount(VIEWER));

        verifyNoInteractions(conversations, messages, realtime, push);
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
