package com.biecuoguo.message.internal;

import com.biecuoguo.domain.User;
import com.biecuoguo.mapper.UserMapper;
import com.biecuoguo.message.api.MessageDtos;
import com.biecuoguo.push.api.PushDeliveryException;
import com.biecuoguo.push.api.PushService;
import com.biecuoguo.realtime.RealtimeHub;
import com.biecuoguo.social.api.BlockQueryApi;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MessageOutboxWorkerTest {
    private static final String EVENT = "0123456789abcdef0123456789abcdef";
    private static final String TOKEN = "fedcba9876543210fedcba9876543210";
    private final MessageOutboxStore store = mock(MessageOutboxStore.class);
    private final MessageMapper messages = mock(MessageMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final BlockQueryApi blocks = mock(BlockQueryApi.class);
    private final RealtimeHub realtime = mock(RealtimeHub.class);
    private final PushService push = mock(PushService.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private MessageOutboxWorker worker;
    private Message message;

    @BeforeEach
    void configureCommittedMessage() {
        worker = new MessageOutboxWorker(store, messages, users, blocks, realtime, push, metrics);
        message = new Message();
        message.setId(99L);
        message.setConversationId(5L);
        message.setSenderId(10L);
        message.setReceiverId(20L);
        message.setContent("fixture content");
        message.setReadFlag(false);
        message.setCreatedAt(LocalDateTime.of(2026, 10, 22, 12, 0));
        when(messages.selectById(99L)).thenReturn(message);
        when(users.selectBatchIds(List.of(10L, 20L))).thenReturn(List.of(user(10L, "active"), user(20L, "active")));
        when(store.owns(any())).thenReturn(true);
        when(store.delivered(any())).thenReturn(true);
        when(store.suppressed(any())).thenReturn(true);
        when(store.retry(any(), any())).thenReturn(true);
        when(store.retry(any(), any(), any())).thenReturn(true);
        when(store.failed(any(), any())).thenReturn(true);
        when(store.summary()).thenReturn(new MessageOutboxStore.Summary(0, 0, 0, 0));
    }

    @Test
    void realtimeCarriesStableEventAndMessageIdsThenAcknowledgesOnlyItsClaim() {
        var claim = next("REALTIME");

        assertEquals(1, worker.drainOnce());

        verify(realtime).publish(eq(20L), eq("message"), org.mockito.ArgumentMatchers.argThat(payload -> {
            Map<?, ?> data = (Map<?, ?>) payload;
            return EVENT.equals(data.get("eventId")) && Long.valueOf(5).equals(data.get("conversationId"))
                    && ((MessageDtos.MessageView) data.get("message")).id().equals(99L);
        }));
        verify(store).delivered(claim);
        verifyNoInteractions(push);
    }

    @Test
    void pushCarriesTheSameRetryIdentityAndMessageReference() {
        var claim = next("PUSH");

        worker.drainOnce();

        verify(push).send(20L, "新私信", "fixture content", Map.of(
                "conversationId", "5", "messageId", "99", "eventId", EVENT));
        verify(store).delivered(claim);
        verifyNoInteractions(realtime);
    }

    @Test
    void committedBlockSuppressesPendingNotificationsWithoutSendingTheirContent() {
        var claim = next("REALTIME");
        when(blocks.isBlockedEitherWay(10L, 20L)).thenReturn(true);

        worker.drainOnce();

        verify(store).suppressed(claim);
        verify(store, never()).delivered(any());
        verifyNoInteractions(realtime, push);
    }

    @Test
    void deletedParticipantSuppressesPendingPushEvenWhenDeletionRemovedBlockRows() {
        var claim = next("PUSH");
        when(users.selectBatchIds(List.of(10L, 20L))).thenReturn(List.of(user(10L, "deleted"), user(20L, "active")));

        worker.drainOnce();

        verify(store).suppressed(claim);
        verifyNoInteractions(realtime, push);
    }

    @Test
    void unknownRelationshipStateIsRetriedWithoutPublishing() {
        var claim = next("REALTIME");
        when(blocks.isBlockedEitherWay(10L, 20L)).thenThrow(new IllegalStateException("dependency unavailable"));

        worker.drainOnce();

        verify(store).retry(claim, "DELIVERY_FAILED");
        verifyNoInteractions(realtime, push);
    }

    @Test
    void expiredClaimCannotBeginANewExternalSend() {
        var claim = next("REALTIME");
        when(store.owns(claim)).thenReturn(false);

        worker.drainOnce();

        verify(store, never()).delivered(any());
        verify(store, never()).retry(any(), any());
        verifyNoInteractions(realtime, push);
        assertEquals(1, metrics.get("unipo.message.outbox.lease.lost").counter().count());
    }

    @Test
    void transientProviderFailurePreservesTheProviderRetryDelay() {
        var claim = next("PUSH");
        doThrow(new PushDeliveryException("APNS_SERVER_ERROR", true, Duration.ofMinutes(15)))
                .when(push).send(any(Long.class), any(), any(), any());

        worker.drainOnce();

        verify(store).retry(claim, "APNS_SERVER_ERROR", Duration.ofMinutes(15));
        verify(store, never()).delivered(any());
    }

    @Test
    void permanentProviderFailureLeavesAnObservableFailureRatherThanAFalseSuccess() {
        var claim = next("PUSH");
        doThrow(new PushDeliveryException("APNS_INVALID_PAYLOAD", false, Duration.ZERO))
                .when(push).send(any(Long.class), any(), any(), any());

        worker.drainOnce();

        verify(store).failed(claim, "APNS_INVALID_PAYLOAD");
        verify(store, never()).delivered(any());
    }

    @Test
    void brokerFailureDoesNotCompleteTheRealtimeDelivery() {
        var claim = next("REALTIME");
        doThrow(new IllegalStateException("transport unavailable")).when(realtime).publish(any(), any(), any());

        worker.drainOnce();

        verify(store).retry(claim, "DELIVERY_FAILED");
        verify(store, never()).delivered(any());
    }

    @Test
    void workerRefusesToBorrowConnectionsWithinABusinessTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class, worker::drainOnce);
            verifyNoInteractions(store, messages, realtime, push);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private MessageOutboxStore.Claim next(String channel) {
        var claim = new MessageOutboxStore.Claim(1, EVENT, 99, channel, TOKEN, 1);
        when(store.claimBatch(eq(1), any())).thenReturn(List.of(claim), List.of());
        return claim;
    }

    private static User user(long id, String status) {
        User user = new User();
        user.setId(id);
        user.setStatus(status);
        return user;
    }
}
