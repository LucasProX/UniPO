package com.biecuoguo.push.internal;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.biecuoguo.push.api.PushDeliveryException;
import com.eatthepath.pushy.apns.ApnsClient;
import com.eatthepath.pushy.apns.PushNotificationResponse;
import com.eatthepath.pushy.apns.util.SimpleApnsPushNotification;
import com.eatthepath.pushy.apns.util.concurrent.PushNotificationFuture;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApnsPushServiceTest {
    private static final long USER = 51L;
    private static final String EVENT_ID = "ab1234567890abcd1234567890abcdef";
    private static final String FIRST_TOKEN = "fixture-device-token-sensitive-a";
    private static final String SECOND_TOKEN = "fixture-device-token-sensitive-b";
    private static final String PRIVATE_TITLE = "fixture-private-title";
    private static final String PRIVATE_BODY = "fixture-private-body";
    private static final Map<String, String> DATA = Map.of("eventId", EVENT_ID, "conversationId", "72");
    private static final PushDeliveryProgress.Batch BATCH = new PushDeliveryProgress.Batch(EVENT_ID, USER, 2L);
    private static final PushDeliveryProgress.Target FIRST_TARGET = new PushDeliveryProgress.Target(1L, FIRST_TOKEN);
    private static final PushDeliveryProgress.Target SECOND_TARGET = new PushDeliveryProgress.Target(2L, SECOND_TOKEN);

    private final PushDeliveryProgress progress = mock(PushDeliveryProgress.class);
    private final ApnsClient client = mock(ApnsClient.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private ApnsPushService service;

    @BeforeEach
    void prepare() {
        service = new ApnsPushService(progress, mapper, client, Duration.ofSeconds(10), () -> 0L);
        when(progress.open(EVENT_ID, USER)).thenReturn(BATCH);
        when(progress.pending(BATCH, PushDeliveryProgress.PAGE_LIMIT)).thenReturn(List.of(FIRST_TARGET));
        when(progress.isCurrentTarget(BATCH, FIRST_TARGET)).thenReturn(true);
        when(progress.isCurrentTarget(BATCH, SECOND_TARGET)).thenReturn(true);
    }

    @Test
    void waitsForProviderResultAndUsesTheSameTrackingAndCollapseIdentityOnRetry() throws Exception {
        List<SimpleApnsPushNotification> submitted = new ArrayList<>();
        when(client.sendNotification(any(SimpleApnsPushNotification.class))).thenAnswer(invocation -> {
            SimpleApnsPushNotification notification = invocation.getArgument(0);
            submitted.add(notification);
            return completed(notification, response(true, 200, null));
        });

        service.send(USER, PRIVATE_TITLE, PRIVATE_BODY, DATA);
        service.send(USER, PRIVATE_TITLE, PRIVATE_BODY, DATA);

        assertEquals(2, submitted.size());
        UUID expected = UUID.fromString("ab123456-7890-abcd-1234-567890abcdef");
        for (var notification : submitted) {
            assertEquals(expected, notification.getApnsId());
            assertEquals(EVENT_ID, notification.getCollapseId());
            assertEquals("alert", notification.getPushType().getHeaderValue());
            var json = mapper.readTree(notification.getPayload());
            assertEquals(PRIVATE_BODY, json.path("aps").path("alert").path("body").asText());
            assertEquals(EVENT_ID, json.path("data").path("eventId").asText());
        }
    }

    @Test
    void aServerFailureRemainsRetryableAfterAnotherDeviceAccepted() {
        when(progress.pending(BATCH, PushDeliveryProgress.PAGE_LIMIT)).thenReturn(List.of(FIRST_TARGET, SECOND_TARGET));
        when(client.sendNotification(any(SimpleApnsPushNotification.class))).thenAnswer(invocation -> {
            SimpleApnsPushNotification notification = invocation.getArgument(0);
            return completed(notification, response(notification.getToken().equals(FIRST_TOKEN),
                    notification.getToken().equals(FIRST_TOKEN) ? 200 : 503, "ServiceUnavailable"));
        });

        PushDeliveryException failure = assertThrows(PushDeliveryException.class,
                () -> service.send(USER, PRIVATE_TITLE, PRIVATE_BODY, DATA));

        assertEquals("APNS_SERVER_ERROR", failure.code());
        assertTrue(failure.retryable());
        assertEquals(Duration.ofMinutes(15), failure.retryAfter());
        assertNull(failure.getCause());
        verify(progress).accepted(BATCH, FIRST_TARGET.id());
        verify(progress, never()).accepted(BATCH, SECOND_TARGET.id());
    }

    @Test
    void rateLimitHasAnExplicitRetryDelay() {
        when(client.sendNotification(any(SimpleApnsPushNotification.class))).thenAnswer(invocation ->
                completed(invocation.getArgument(0), response(false, 429, "TooManyRequests")));

        PushDeliveryException failure = assertThrows(PushDeliveryException.class,
                () -> service.send(USER, PRIVATE_TITLE, PRIVATE_BODY, DATA));

        assertEquals("APNS_RATE_LIMITED", failure.code());
        assertTrue(failure.retryable());
        assertEquals(Duration.ofMinutes(1), failure.retryAfter());
    }

    @Test
    void permanentlyRejectedPayloadIsVisibleAsANonRetryableOutcome() {
        when(client.sendNotification(any(SimpleApnsPushNotification.class))).thenAnswer(invocation ->
                completed(invocation.getArgument(0), response(false, 400, "PayloadTooLarge")));

        PushDeliveryException failure = assertThrows(PushDeliveryException.class,
                () -> service.send(USER, PRIVATE_TITLE, PRIVATE_BODY, DATA));

        assertEquals("APNS_REJECTED", failure.code());
        assertFalse(failure.retryable());
    }

    @Test
    void invalidDeviceDoesNotBlockOtherDevicesOrBlindlyDeleteANewerRegistration() {
        when(progress.pending(BATCH, PushDeliveryProgress.PAGE_LIMIT)).thenReturn(List.of(FIRST_TARGET, SECOND_TARGET));
        List<SimpleApnsPushNotification> submitted = new ArrayList<>();
        when(client.sendNotification(any(SimpleApnsPushNotification.class))).thenAnswer(invocation -> {
            SimpleApnsPushNotification notification = invocation.getArgument(0);
            submitted.add(notification);
            return completed(notification, response(notification.getToken().equals(SECOND_TOKEN),
                    notification.getToken().equals(SECOND_TOKEN) ? 200 : 410, "Unregistered"));
        });

        service.send(USER, PRIVATE_TITLE, PRIVATE_BODY, DATA);

        assertEquals(2, submitted.size());
        verify(progress).invalid(BATCH, FIRST_TARGET.id());
        verify(progress).accepted(BATCH, SECOND_TARGET.id());
    }

    @Test
    void unconfiguredClientAndProgressLookupFailureNeverReportSuccessfulDelivery() {
        ApnsPushService unconfigured = new ApnsPushService(progress, mapper, "", "", "", "com.unipo.app", false);
        assertEquals("APNS_UNAVAILABLE", assertThrows(PushDeliveryException.class,
                () -> unconfigured.send(USER, PRIVATE_TITLE, PRIVATE_BODY, DATA)).code());
        when(progress.open(EVENT_ID, USER)).thenThrow(new IllegalStateException(FIRST_TOKEN + PRIVATE_BODY));

        var failure = assertThrows(PushDeliveryException.class,
                () -> service.send(USER, PRIVATE_TITLE, PRIVATE_BODY, DATA));
        assertEquals("APNS_UNAVAILABLE", failure.getMessage());
        assertNull(failure.getCause());
    }

    @Test
    void transportExceptionContainsNoSensitiveProviderCause() {
        when(client.sendNotification(any(SimpleApnsPushNotification.class))).thenAnswer(invocation -> {
            var future = new PushNotificationFuture<SimpleApnsPushNotification,
                    PushNotificationResponse<SimpleApnsPushNotification>>(invocation.getArgument(0));
            future.completeExceptionally(new IllegalStateException(FIRST_TOKEN + PRIVATE_BODY));
            return future;
        });

        var failure = assertThrows(PushDeliveryException.class,
                () -> service.send(USER, PRIVATE_TITLE, PRIVATE_BODY, DATA));

        assertEquals("APNS_UNAVAILABLE", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(0, failure.getStackTrace().length);
    }

    @Test
    void everyDeviceSharesOneDeadlineAndTimeoutCancelsUnfinishedFutures() {
        when(progress.pending(BATCH, PushDeliveryProgress.PAGE_LIMIT)).thenReturn(List.of(FIRST_TARGET, SECOND_TARGET));
        AtomicLong tick = new AtomicLong();
        service = new ApnsPushService(progress, mapper, client, Duration.ofSeconds(10),
                () -> tick.getAndAdd(TimeUnit.SECONDS.toNanos(1)));
        List<TimingFuture> futures = new ArrayList<>();
        when(client.sendNotification(any(SimpleApnsPushNotification.class))).thenAnswer(invocation -> {
            TimingFuture future = new TimingFuture(invocation.getArgument(0), futures.isEmpty());
            futures.add(future);
            return future;
        });

        var failure = assertThrows(PushDeliveryException.class,
                () -> service.send(USER, PRIVATE_TITLE, PRIVATE_BODY, DATA));

        assertEquals("APNS_TIMEOUT", failure.code());
        assertEquals(2, futures.size());
        assertEquals(TimeUnit.SECONDS.toNanos(5), futures.get(0).waitBudgetNanos);
        assertEquals(TimeUnit.SECONDS.toNanos(4), futures.get(1).waitBudgetNanos);
        assertTrue(futures.get(1).isCancelled());
    }

    @Test
    void invalidEventAndSerializationFailuresAreSafePermanentOutcomes() throws Exception {
        var badId = assertThrows(PushDeliveryException.class,
                () -> service.send(USER, PRIVATE_TITLE, PRIVATE_BODY, Map.of("eventId", "not-an-event")));
        assertEquals("APNS_INVALID_PAYLOAD", badId.code());
        assertFalse(badId.retryable());

        ObjectMapper broken = mock(ObjectMapper.class);
        doThrow(new JsonProcessingException(FIRST_TOKEN + PRIVATE_BODY) {}).when(broken).writeValueAsString(any());
        ApnsPushService brokenService = new ApnsPushService(progress, broken, client, Duration.ofSeconds(1), () -> 0L);
        var serialization = assertThrows(PushDeliveryException.class,
                () -> brokenService.send(USER, PRIVATE_TITLE, PRIVATE_BODY, DATA));
        assertEquals("APNS_INVALID_PAYLOAD", serialization.getMessage());
        assertNull(serialization.getCause());
        verify(client, never()).sendNotification(any(SimpleApnsPushNotification.class));
    }

    @Test
    void anotherPendingPageRequestsImmediateDurableContinuation() {
        when(client.sendNotification(any(SimpleApnsPushNotification.class))).thenAnswer(invocation ->
                completed(invocation.getArgument(0), response(true, 200, null)));
        when(progress.hasPending(BATCH)).thenReturn(true);

        var failure = assertThrows(PushDeliveryException.class,
                () -> service.send(USER, PRIVATE_TITLE, PRIVATE_BODY, DATA));

        assertEquals("APNS_MORE_TARGETS", failure.code());
        assertTrue(failure.retryable());
        assertEquals(Duration.ZERO, failure.retryAfter());
        verify(progress).accepted(BATCH, FIRST_TARGET.id());
    }

    @Test
    void reboundTargetIsSkippedBeforeProviderSubmission() {
        when(progress.isCurrentTarget(BATCH, FIRST_TARGET)).thenReturn(false);

        service.send(USER, PRIVATE_TITLE, PRIVATE_BODY, DATA);

        verify(client, never()).sendNotification(any(SimpleApnsPushNotification.class));
        verify(progress, never()).accepted(any(), anyLong());
    }

    @Test
    void anAcceptedProviderResultWithoutADurableReceiptRemainsRetryable() {
        when(client.sendNotification(any(SimpleApnsPushNotification.class))).thenAnswer(invocation ->
                completed(invocation.getArgument(0), response(true, 200, null)));
        doThrow(new IllegalStateException(FIRST_TOKEN + PRIVATE_BODY)).when(progress).accepted(BATCH, FIRST_TARGET.id());

        var failure = assertThrows(PushDeliveryException.class,
                () -> service.send(USER, PRIVATE_TITLE, PRIVATE_BODY, DATA));

        assertEquals("APNS_UNAVAILABLE", failure.code());
        assertTrue(failure.retryable());
        assertNull(failure.getCause());
    }

    @Test
    void deliveryCannotHoldAnOuterBusinessTransactionDuringProviderWaits() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class, () -> service.send(USER, PRIVATE_TITLE, PRIVATE_BODY, DATA));
            verify(client, never()).sendNotification(any(SimpleApnsPushNotification.class));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void loggingProviderNeverWritesNotificationContentsOrDeviceCredentials() {
        Logger logger = (Logger) LoggerFactory.getLogger(LoggingPushService.class);
        var previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        logger.addAppender(appender);
        try {
            new LoggingPushService().send(USER, PRIVATE_TITLE, PRIVATE_BODY, Map.of("deviceToken", FIRST_TOKEN));

            assertEquals(1, appender.list.size());
            String event = appender.list.getFirst().getFormattedMessage();
            assertFalse(event.contains(PRIVATE_TITLE));
            assertFalse(event.contains(PRIVATE_BODY));
            assertFalse(event.contains(FIRST_TOKEN));
            assertNull(appender.list.getFirst().getThrowableProxy());
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    private static PushNotificationFuture<SimpleApnsPushNotification,
            PushNotificationResponse<SimpleApnsPushNotification>> completed(SimpleApnsPushNotification notification,
                                                                          PushNotificationResponse<SimpleApnsPushNotification> response) {
        var future = new PushNotificationFuture<SimpleApnsPushNotification,
                PushNotificationResponse<SimpleApnsPushNotification>>(notification);
        future.complete(response);
        return future;
    }

    @SuppressWarnings("unchecked")
    private static PushNotificationResponse<SimpleApnsPushNotification> response(boolean accepted, int status, String reason) {
        var response = (PushNotificationResponse<SimpleApnsPushNotification>) mock(PushNotificationResponse.class);
        when(response.isAccepted()).thenReturn(accepted);
        when(response.getStatusCode()).thenReturn(status);
        when(response.getRejectionReason()).thenReturn(Optional.ofNullable(reason));
        return response;
    }

    private static final class TimingFuture extends PushNotificationFuture<SimpleApnsPushNotification,
            PushNotificationResponse<SimpleApnsPushNotification>> {
        private final boolean accepted;
        long waitBudgetNanos;

        TimingFuture(SimpleApnsPushNotification notification, boolean accepted) {
            super(notification);
            this.accepted = accepted;
        }

        @Override
        public PushNotificationResponse<SimpleApnsPushNotification> get(long timeout, TimeUnit unit) throws TimeoutException {
            waitBudgetNanos = unit.toNanos(timeout);
            if (accepted) { return response(true, 200, null); }
            throw new TimeoutException();
        }
    }
}
