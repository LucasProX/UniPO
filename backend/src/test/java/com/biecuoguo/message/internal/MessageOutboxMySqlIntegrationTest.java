package com.biecuoguo.message.internal;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.biecuoguo.mapper.SocialRelationshipLockMapper;
import com.biecuoguo.mapper.UserBlockMapper;
import com.biecuoguo.mapper.UserFollowMapper;
import com.biecuoguo.mapper.UserMapper;
import com.biecuoguo.mapper.UserReportMapper;
import com.biecuoguo.message.api.MessageDtos;
import com.biecuoguo.push.api.PushDeliveryException;
import com.biecuoguo.push.api.PushService;
import com.biecuoguo.realtime.RealtimeHub;
import com.biecuoguo.security.CurrentUser;
import com.biecuoguo.service.PresenceService;
import com.biecuoguo.service.SocialService;
import com.biecuoguo.state.MemoryPresenceStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/** Persistent outbox and independent worker recovery against a new, ownership-checked MySQL only. */
@EnabledIfEnvironmentVariable(named = "UNIPO_DP14_OUTBOX_MYSQL_INTEGRATION", matches = "1")
@Timeout(40)
class MessageOutboxMySqlIntegrationTest {
    private static final Duration LEASE = Duration.ofSeconds(60);
    private static final AtomicInteger PUBLIC_UID = new AtomicInteger(75_000_000);
    private static RuntimeContext runtime;
    private static JdbcTemplate jdbc;
    private static long schoolId;
    private final List<Long> ownedUsers = new ArrayList<>();

    @BeforeAll
    static void migrateOnlyTheNewOwnedFixture() {
        HikariDataSource pool = fixturePool(4);
        jdbc = new JdbcTemplate(pool);
        assertEquals(List.of("dp02_fixture_marker"), jdbc.queryForList("SHOW TABLES", String.class),
                "Migration requires the newly created empty fixture, never an existing application schema");
        Flyway flyway = Flyway.configure().dataSource(pool).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("0").createSchemas(false).cleanDisabled(true).load();
        flyway.migrate();
        assertEquals(0, flyway.info().pending().length);
        assertTrue(flyway.validateWithResult().validationSuccessful);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history "
                + "WHERE version = '23' AND success = 1", Integer.class));
        runtime = new RuntimeContext(pool);
        assertTrue(AopUtils.isAopProxy(runtime.messages()));
        assertTrue(AopUtils.isAopProxy(runtime.store()));
        String code = "dp14-" + UUID.randomUUID();
        jdbc.update("INSERT INTO schools (name, code, created_at, updated_at) VALUES (?, ?, NOW(), NOW())",
                "Outbox fixture", code);
        Long createdSchool = jdbc.queryForObject("SELECT id FROM schools WHERE code = ?", Long.class, code);
        assertNotNull(createdSchool);
        schoolId = createdSchool;
        System.out.println("DP14 owned MySQL V1-V23 migrations, bounded Hikari pools and transaction proxies verified");
    }

    @AfterEach
    void removeOnlyOwnedMessagesAndTheirDeliveryIntents() {
        for (Long user : ownedUsers) { jdbc.update("DELETE FROM users WHERE id = ?", user); }
        runtime.probe().reset();
    }

    @AfterAll
    static void closeFixture() {
        try {
            if (schoolId != 0) { jdbc.update("DELETE FROM schools WHERE id = ?", schoolId); }
        } finally {
            if (runtime != null) { runtime.close(); }
        }
    }

    @Test
    void aCommittedMessageHasExactlyTwoStableIdempotentDeliveryIntents() {
        Pair pair = newPair();
        MessageDtos.MessageView message = send(pair);
        List<Map<String, Object>> before = deliveryFacts(message.id());
        assertEquals(2, before.size());
        assertEquals(Set.of("REALTIME", "PUSH"), new HashSet<>(before.stream().map(row -> (String) row.get("channel")).toList()));
        assertEquals(2, new HashSet<>(before.stream().map(row -> row.get("event_id")).toList()).size());
        assertTrue(before.stream().allMatch(row -> "PENDING".equals(row.get("state"))));
        transaction().executeWithoutResult(status -> runtime.store().enqueue(message.id()));
        assertEquals(before, deliveryFacts(message.id()), "Enqueue retries preserve the original per-channel event IDs");
        assertEquals(0, runtime.probe().attempts.size(), "The message request must not call either transport");
    }

    @Test
    void rollingBackTheMessageAlsoRollsBackBothDeliveryIntents() {
        Pair pair = newPair();
        AtomicReference<Long> messageId = new AtomicReference<>();
        transaction().executeWithoutResult(status -> {
            messageId.set(send(pair).id());
            assertEquals(2, deliveryFacts(messageId.get()).size());
            status.setRollbackOnly();
        });
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM messages WHERE id = ?", Integer.class, messageId.get()));
        assertTrue(deliveryFacts(messageId.get()).isEmpty());
        assertEquals(0, runtime.worker().drainOnce());
        assertEquals(0, runtime.probe().attempts.size());
    }

    @Test
    void aSeparateWorkerCannotObserveTheUncommittedMessageOrItsIntents() {
        Pair pair = newPair();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            transaction().executeWithoutResult(status -> {
                send(pair);
                assertEquals(0, future(worker.submit(() -> runtime.worker().drainOnce())));
                assertEquals(0, runtime.probe().attempts.size());
            });
            assertEquals(2, runtime.worker().drainOnce());
            assertEquals(2, runtime.probe().attempts.size());
        } finally { stop(worker); }
    }

    @Test
    void twoWorkersHoldOverlappingClaimTransactionsWithoutClaimingAnyRowTwice() {
        Pair pair = newPair();
        for (int index = 0; index < 8; index++) { send(pair); }
        MessageOutboxMapper real = runtime.mapper(MessageOutboxMapper.class);
        CountDownLatch selected = new CountDownLatch(2);
        MessageOutboxMapper gated = mock(MessageOutboxMapper.class, delegatesTo(real));
        doAnswer(call -> {
            var ready = real.ready(call.getArgument(0));
            selected.countDown();
            await(selected);
            return ready;
        }).when(gated).ready(anyInt());
        MessageOutboxStore first = new MessageOutboxStore(gated, runtime.manager());
        MessageOutboxStore second = new MessageOutboxStore(gated, runtime.manager());
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<List<MessageOutboxStore.Claim>> a = workers.submit(() -> first.claimBatch(50, LEASE));
            Future<List<MessageOutboxStore.Claim>> b = workers.submit(() -> second.claimBatch(50, LEASE));
            List<MessageOutboxStore.Claim> claims = new ArrayList<>(future(a));
            claims.addAll(future(b));
            assertEquals(16, claims.size());
            assertEquals(16, new HashSet<>(claims.stream().map(MessageOutboxStore.Claim::id).toList()).size());
            assertEquals(16, jdbc.queryForObject("SELECT COUNT(*) FROM message_delivery_outbox WHERE state = 'SENDING' AND attempts = 1",
                    Integer.class));
        } finally { stop(workers); }
    }

    @Test
    void anotherStoreRecoversAnExpiredLeaseAndRejectsEveryLateOriginalOwnerWrite() {
        Pair pair = newPair();
        MessageDtos.MessageView message = send(pair);
        List<MessageOutboxStore.Claim> claims = runtime.store().claimBatch(2, LEASE);
        MessageOutboxStore.Claim old = claims.getFirst();
        assertTrue(runtime.store().owns(old));
        expireLease(old.id());
        MessageOutboxStore rebuilt = new MessageOutboxStore(runtime.mapper(MessageOutboxMapper.class), runtime.manager());
        var replacement = rebuilt.claimBatch(1, LEASE).getFirst();

        assertEquals(old.id(), replacement.id());
        assertEquals(old.eventId(), replacement.eventId());
        assertEquals(message.id(), replacement.messageId());
        assertEquals(2, replacement.attempts());
        assertNotEquals(old.claimToken(), replacement.claimToken());
        assertFalse(runtime.store().owns(old));
        assertFalse(runtime.store().delivered(old));
        assertFalse(runtime.store().suppressed(old));
        assertFalse(runtime.store().retry(old, "DELIVERY_FAILED"));
        assertFalse(runtime.store().failed(old, "DELIVERY_FAILED"));
        assertTrue(rebuilt.delivered(replacement));
        assertTrue(runtime.store().suppressed(claims.get(1)));
    }

    @Test
    void aSeparateJvmExitsAfterPublicationAndANewWorkerRecoversItsUnacknowledgedLease() throws Exception {
        Pair pair = newPair();
        MessageDtos.MessageView message = send(pair);
        String eventId = eventId(message.id(), "REALTIME");
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process child = new ProcessBuilder(java, "-cp", classPath, CrashAfterRealtime.class.getName())
                .redirectErrorStream(true).start();
        try {
            assertTrue(child.waitFor(15, TimeUnit.SECONDS), "The isolated publisher process must exit after its first sink");
            String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, child.exitValue(), "Child process could not reach the crash gate: " + output);
            assertTrue(output.contains("DP14_CHILD_DELIVERY event_id=" + eventId + " message_id=" + message.id()),
                    "The child must actually enter the transport before terminating");
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                assertTrue(child.waitFor(5, TimeUnit.SECONDS));
            }
        }
        assertEquals("SENDING", state(message.id(), "REALTIME"));
        assertEquals("PENDING", state(message.id(), "PUSH"));
        long id = outboxId(message.id(), "REALTIME");
        expireLease(id);
        try (RuntimeContext recovered = new RuntimeContext(fixturePool(1))) {
            assertEquals(2, recovered.worker().drainOnce());
            assertEquals(2, recovered.probe().attempts.size());
            assertTrue(recovered.probe().attempts.stream().anyMatch(item -> item.channel().equals("REALTIME")
                    && item.eventId().equals(eventId) && item.messageId() == message.id()));
        }
        assertEquals("DELIVERED", state(message.id(), "REALTIME"));
        assertEquals("DELIVERED", state(message.id(), "PUSH"));
        assertEquals(2, attempts(message.id(), "REALTIME"));
        assertEquals(1, attempts(message.id(), "PUSH"));
        assertEquals(eventId, eventId(message.id(), "REALTIME"));
        System.out.println("DP14 separate JVM halted after transport entry; database lease recovered with stable event ID");
    }

    @Test
    void aTemporaryRealtimeFailureRetriesOnlyThatChannelWithTheSameEventId() {
        Pair pair = newPair();
        MessageDtos.MessageView message = send(pair);
        String eventId = eventId(message.id(), "REALTIME");
        runtime.probe().realtimeFailure.set(new IllegalStateException("synthetic transport unavailable"));
        assertEquals(2, runtime.worker().drainOnce());
        assertEquals("PENDING", state(message.id(), "REALTIME"));
        assertEquals("DELIVERED", state(message.id(), "PUSH"));
        assertEquals("DELIVERY_FAILED", error(message.id(), "REALTIME"));
        assertEquals(0, runtime.worker().drainOnce(), "The backoff must prevent immediate hot retries");
        makeReady(message.id(), "REALTIME");
        assertEquals(1, runtime.worker().drainOnce());
        assertEquals("DELIVERED", state(message.id(), "REALTIME"));
        assertEquals(2, runtime.probe().attempts.stream().filter(item -> item.channel().equals("REALTIME")
                && item.eventId().equals(eventId)).count());
        assertEquals(1, runtime.probe().attempts.stream().filter(item -> item.channel().equals("PUSH")).count());
    }

    @Test
    void retryablePushFailurePersistsTheProviderMinimumDelayAndKeepsRealtimeCompleted() {
        Pair pair = newPair();
        MessageDtos.MessageView message = send(pair);
        runtime.probe().pushFailure.set(new PushDeliveryException("APNS_SERVER_ERROR", true, Duration.ofSeconds(900)));
        assertEquals(2, runtime.worker().drainOnce());
        assertEquals("DELIVERED", state(message.id(), "REALTIME"));
        assertEquals("PENDING", state(message.id(), "PUSH"));
        assertEquals("APNS_SERVER_ERROR", error(message.id(), "PUSH"));
        assertTrue(jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(6), available_at) "
                + "FROM message_delivery_outbox WHERE message_id = ? AND channel = 'PUSH'", Long.class, message.id()) >= 899);
        assertEquals(0, runtime.worker().drainOnce());
        makeReady(message.id(), "PUSH");
        assertEquals(1, runtime.worker().drainOnce());
        assertEquals("DELIVERED", state(message.id(), "PUSH"));
        assertEquals(1, runtime.probe().attempts.stream().filter(item -> item.channel().equals("REALTIME")).count());
    }

    @Test
    void anotherDevicePageContinuesImmediatelyEvenAfterALongRetryHistory() {
        Pair pair = newPair();
        MessageDtos.MessageView message = send(pair);
        String event = eventId(message.id(), "PUSH");
        jdbc.update("UPDATE message_delivery_outbox SET attempts = 100 WHERE message_id = ? AND channel = 'PUSH'",
                message.id());
        double continued = runtime.metrics().get("unipo.message.outbox.continued").counter().count();
        double retried = runtime.metrics().get("unipo.message.outbox.retried").counter().count();
        runtime.probe().pushFailure.set(new PushDeliveryException("APNS_MORE_TARGETS", true, Duration.ZERO));

        assertEquals(3, runtime.worker().drainOnce(), "The next device page must not inherit failure backoff");

        assertEquals("DELIVERED", state(message.id(), "REALTIME"));
        assertEquals("DELIVERED", state(message.id(), "PUSH"));
        assertEquals(102, attempts(message.id(), "PUSH"));
        assertEquals(event, eventId(message.id(), "PUSH"));
        assertEquals(2, runtime.probe().attempts.stream().filter(item -> item.channel().equals("PUSH")
                && item.eventId().equals(event)).count());
        assertEquals(continued + 1, runtime.metrics().get("unipo.message.outbox.continued").counter().count());
        assertEquals(retried, runtime.metrics().get("unipo.message.outbox.retried").counter().count());
    }

    @Test
    void aPermanentProviderFailureIsVisibleAndDoesNotPreventTheOtherChannel() {
        Pair pair = newPair();
        MessageDtos.MessageView message = send(pair);
        runtime.probe().pushFailure.set(new PushDeliveryException("APNS_REJECTED", false, Duration.ZERO));
        assertEquals(2, runtime.worker().drainOnce());
        assertEquals("DELIVERED", state(message.id(), "REALTIME"));
        assertEquals("FAILED", state(message.id(), "PUSH"));
        assertEquals("APNS_REJECTED", error(message.id(), "PUSH"));
        assertEquals(1L, runtime.store().summary().failed());
        assertEquals(1.0, runtime.metrics().get("unipo.message.outbox.failed").counter().count());
        assertEquals(1.0, runtime.metrics().get("unipo.message.outbox.failed.current").gauge().value());
        assertEquals(0, runtime.worker().drainOnce());
    }

    @Test
    void aBlockCommittedBeforeWorkerDeliverySuppressesBothChannelsPermanently() {
        Pair pair = newPair();
        MessageDtos.MessageView message = send(pair);
        runtime.social().block(uid(pair.sender()), pair.receiver());
        assertEquals(2, runtime.worker().drainOnce());
        assertEquals("SUPPRESSED", state(message.id(), "REALTIME"));
        assertEquals("SUPPRESSED", state(message.id(), "PUSH"));
        assertTrue(runtime.probe().attempts.isEmpty());
        runtime.social().unblock(uid(pair.sender()), pair.receiver());
        assertEquals(0, runtime.worker().drainOnce());
        assertTrue(runtime.probe().attempts.isEmpty());
        assertEquals(1, runtime.messages().messages(pair.conversation(), pair.receiver()).size());
    }

    @Test
    void aDeletedOrBannedRecipientCannotReceivePreviouslyQueuedMessageContent() {
        for (String status : List.of("deleted", "banned")) {
            Pair pair = newPair();
            MessageDtos.MessageView message = send(pair);
            jdbc.update("UPDATE users SET status = ? WHERE id = ?", status, pair.receiver().id());
            assertEquals(2, runtime.worker().drainOnce());
            assertEquals("SUPPRESSED", state(message.id(), "REALTIME"));
            assertEquals("SUPPRESSED", state(message.id(), "PUSH"));
        }
        assertTrue(runtime.probe().attempts.isEmpty());
    }

    @Test
    void boundedOneConnectionPoolCompletesCreateSendHistoryAndIndependentWorkerDelivery() {
        CurrentUser sender = newUser();
        CurrentUser receiver = newUser();
        try (RuntimeContext single = new RuntimeContext(fixturePool(1))) {
            assertEquals(1, single.pool().getMaximumPoolSize());
            var conversation = single.messages().create(new MessageDtos.CreateConversationRequest(uid(receiver), null), sender);
            var message = single.messages().send(conversation.id(), new MessageDtos.SendMessageRequest("one connection"), sender);
            assertEquals(1, single.messages().messages(conversation.id(), receiver).size());
            assertEquals(0, single.pool().getHikariPoolMXBean().getActiveConnections());
            assertEquals(2, single.worker().drainOnce());
            assertEquals(2, single.probe().attempts.size());
            assertTrue(single.probe().attempts.stream().allMatch(Attempt::outsideTransaction));
            assertTrue(single.probe().attempts.stream().allMatch(Attempt::connectionAvailable));
            assertEquals("DELIVERED", state(message.id(), "REALTIME"));
            assertEquals("DELIVERED", state(message.id(), "PUSH"));
            assertEquals(0, single.pool().getHikariPoolMXBean().getActiveConnections());
        }
    }

    @Test
    void workerAndLeaseOperationsRejectBeingCalledInsideABusinessTransaction() {
        Pair pair = newPair();
        var message = send(pair);
        var claim = runtime.store().claimBatch(1, LEASE).getFirst();
        transaction().executeWithoutResult(status -> {
            assertThrows(IllegalStateException.class, () -> runtime.worker().drainOnce());
            assertThrows(IllegalStateException.class, () -> runtime.store().claimBatch(1, LEASE));
            assertThrows(IllegalStateException.class, () -> runtime.store().owns(claim));
            assertThrows(IllegalStateException.class, () -> runtime.store().delivered(claim));
        });
        assertEquals("SENDING", state(message.id(), claim.channel()));
        assertTrue(runtime.store().owns(claim));
    }

    @Test
    void aWorkerPassRemainsBoundedAndTheNextPassProcessesTheRemainingIntents() {
        Pair pair = newPair();
        for (int index = 0; index < 11; index++) { send(pair); }
        assertEquals(20, runtime.worker().drainOnce());
        assertEquals(2L, runtime.store().summary().pending());
        assertEquals(2, runtime.worker().drainOnce());
        assertEquals(0L, runtime.store().summary().pending());
        assertEquals(22, runtime.probe().attempts.size());
    }

    @Test
    void leaseExpiryWithoutAReplacementStillRejectsAcknowledgementAndRetry() {
        Pair pair = newPair();
        send(pair);
        var claim = runtime.store().claimBatch(1, LEASE).getFirst();
        expireLease(claim.id());
        assertFalse(runtime.store().owns(claim));
        assertFalse(runtime.store().delivered(claim));
        assertFalse(runtime.store().retry(claim, "DELIVERY_FAILED"));
        assertFalse(runtime.store().failed(claim, "DELIVERY_FAILED"));
        assertFalse(runtime.store().suppressed(claim));
    }

    @Test
    void retryMetadataKeepsOnlyStableCodesAndUsesTheDatabaseBackoffClock() {
        Pair pair = newPair();
        MessageDtos.MessageView message = send(pair);
        var claim = runtime.store().claimBatch(1, LEASE).getFirst();
        assertTrue(runtime.store().retry(claim, "unrecognized-provider-response"));
        assertEquals("DELIVERY_FAILED", error(message.id(), claim.channel()));
        assertEquals("PENDING", state(message.id(), claim.channel()));
        assertTrue(jdbc.queryForObject("SELECT available_at > UTC_TIMESTAMP(6) FROM message_delivery_outbox WHERE id = ?",
                Boolean.class, claim.id()));
        assertEquals(claim.eventId(), eventId(message.id(), claim.channel()));
        assertEquals(1, attempts(message.id(), claim.channel()));
    }

    @Test
    void deletingAnOwnedMessageCascadesOnlyItsDeliveryMetadata() {
        Pair pair = newPair();
        var removed = send(pair);
        var kept = send(pair);
        jdbc.update("DELETE FROM messages WHERE id = ?", removed.id());
        assertTrue(deliveryFacts(removed.id()).isEmpty());
        assertEquals(2, deliveryFacts(kept.id()).size());
        assertEquals(2, runtime.worker().drainOnce());
        assertEquals(2, runtime.probe().attempts.size());
        assertTrue(runtime.probe().attempts.stream().allMatch(item -> item.messageId() == kept.id()));
    }

    private Pair newPair() {
        CurrentUser sender = newUser();
        CurrentUser receiver = newUser();
        var conversation = runtime.messages().create(new MessageDtos.CreateConversationRequest(uid(receiver), null), sender);
        return new Pair(sender, receiver, conversation.id());
    }

    private MessageDtos.MessageView send(Pair pair) {
        return runtime.messages().send(pair.conversation(), new MessageDtos.SendMessageRequest("outbox fixture"), pair.sender());
    }

    private CurrentUser newUser() {
        String account = "outbox-" + UUID.randomUUID() + "@example.invalid";
        jdbc.update("INSERT INTO users (public_uid, email, password_hash, nickname, school_id, role, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'USER', 'active', NOW(), NOW())",
                Integer.toString(PUBLIC_UID.getAndIncrement()), account, UUID.randomUUID().toString(),
                "ob-" + UUID.randomUUID(), schoolId);
        Long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, account);
        assertNotNull(id);
        ownedUsers.add(id);
        return new CurrentUser(id, account, "USER");
    }

    private String uid(CurrentUser user) {
        return jdbc.queryForObject("SELECT public_uid FROM users WHERE id = ?", String.class, user.id());
    }

    private List<Map<String, Object>> deliveryFacts(long messageId) {
        return jdbc.queryForList("SELECT event_id, channel, state FROM message_delivery_outbox WHERE message_id = ? ORDER BY id", messageId);
    }

    private String state(long messageId, String channel) {
        return textColumn(messageId, channel, "state");
    }

    private String eventId(long messageId, String channel) {
        return textColumn(messageId, channel, "event_id");
    }

    private String error(long messageId, String channel) {
        return textColumn(messageId, channel, "last_error");
    }

    private String textColumn(long messageId, String channel, String column) {
        if (!Set.of("state", "event_id", "last_error").contains(column)) { throw new IllegalArgumentException(); }
        return jdbc.queryForObject("SELECT " + column + " FROM message_delivery_outbox WHERE message_id = ? AND channel = ?",
                String.class, messageId, channel);
    }

    private long outboxId(long messageId, String channel) {
        return jdbc.queryForObject("SELECT id FROM message_delivery_outbox WHERE message_id = ? AND channel = ?",
                Long.class, messageId, channel);
    }

    private int attempts(long messageId, String channel) {
        return jdbc.queryForObject("SELECT attempts FROM message_delivery_outbox WHERE message_id = ? AND channel = ?",
                Integer.class, messageId, channel);
    }

    private void expireLease(long id) {
        jdbc.update("UPDATE message_delivery_outbox SET lease_until = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(6)) WHERE id = ?", id);
    }

    private void makeReady(long messageId, String channel) {
        jdbc.update("UPDATE message_delivery_outbox SET available_at = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(6)) "
                + "WHERE message_id = ? AND channel = ? AND state = 'PENDING'", messageId, channel);
    }

    private TransactionTemplate transaction() {
        TransactionTemplate template = new TransactionTemplate(runtime.manager());
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(10);
        return template;
    }

    private static HikariDataSource fixturePool(int maximum) {
        assertEquals("1", System.getenv("UNIPO_DP02_MYSQL_INTEGRATION"));
        assertEquals("1", System.getenv("UNIPO_DP14_OUTBOX_MYSQL_INTEGRATION"));
        assertEquals("127.0.0.1", System.getenv("UNIPO_DP02_MYSQL_HOST"));
        assertEquals("22306", System.getenv("UNIPO_DP02_MYSQL_PORT"));
        String run = System.getenv("UNIPO_DP02_MYSQL_RUN_ID");
        assertNotNull(run);
        assertTrue(run.matches("[0-9a-f]{16}"));
        String schema = System.getenv("UNIPO_DP02_MYSQL_SCHEMA");
        assertEquals("unipo_dp02_" + run, schema);
        assertEquals("unipo_fixture", System.getenv("UNIPO_DP02_MYSQL_USER"));
        String password = System.getenv("UNIPO_DP02_MYSQL_PASSWORD");
        assertNotNull(password);
        assertTrue(password.matches("[0-9a-f]{48}"));
        HikariConfig configuration = new HikariConfig();
        configuration.setJdbcUrl("jdbc:mysql://127.0.0.1:22306/" + schema
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=2000&socketTimeout=10000");
        configuration.setUsername("unipo_fixture");
        configuration.setPassword(password);
        configuration.setPoolName("dp14-" + UUID.randomUUID());
        configuration.setMaximumPoolSize(maximum);
        configuration.setMinimumIdle(0);
        configuration.setConnectionTimeout(1_000);
        configuration.setValidationTimeout(500);
        HikariDataSource source = new HikariDataSource(configuration);
        try {
            JdbcTemplate ownership = new JdbcTemplate(source);
            assertEquals(schema, ownership.queryForObject("SELECT DATABASE()", String.class));
            assertEquals(run, ownership.queryForObject("SELECT LOWER(HEX(run_id)) FROM dp02_fixture_marker", String.class));
            return source;
        } catch (RuntimeException | Error failure) {
            source.close();
            throw failure;
        }
    }

    private static void await(CountDownLatch gate) {
        try { assertTrue(gate.await(5, TimeUnit.SECONDS), "Fixture gate did not progress"); }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Fixture gate interrupted", failure);
        }
    }

    private static <T> T future(Future<T> result) {
        try { return result.get(12, TimeUnit.SECONDS); }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Fixture action interrupted", failure);
        } catch (ExecutionException failure) { throw new IllegalStateException("Fixture action failed", failure.getCause()); }
        catch (java.util.concurrent.TimeoutException failure) { throw new IllegalStateException("Fixture action timed out", failure); }
    }

    private static void stop(ExecutorService workers) {
        workers.shutdownNow();
        try { assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "Fixture workers must terminate before cleanup"); }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Fixture shutdown interrupted", failure);
        }
    }

    record Pair(CurrentUser sender, CurrentUser receiver, long conversation) {}
    record Attempt(String channel, String eventId, long messageId, boolean outsideTransaction, boolean connectionAvailable) {}

    static final class Probe {
        final HikariDataSource pool;
        final ConcurrentLinkedQueue<Attempt> attempts = new ConcurrentLinkedQueue<>();
        final AtomicReference<RuntimeException> realtimeFailure = new AtomicReference<>();
        final AtomicReference<RuntimeException> pushFailure = new AtomicReference<>();
        volatile boolean haltAfterRealtime;

        Probe(HikariDataSource pool) { this.pool = pool; }

        void record(String channel, String eventId, long messageId) {
            boolean freeConnection;
            try (Connection ignored = pool.getConnection()) { freeConnection = true; }
            catch (java.sql.SQLException failure) { throw new IllegalStateException("Transport retained the only DB connection", failure); }
            attempts.add(new Attempt(channel, eventId, messageId,
                    !TransactionSynchronizationManager.isActualTransactionActive(), freeConnection));
            if (haltAfterRealtime && channel.equals("REALTIME")) {
                System.out.println("DP14_CHILD_DELIVERY event_id=" + eventId + " message_id=" + messageId);
                System.out.flush();
                Runtime.getRuntime().halt(0); // Intentional child-process crash before the worker can acknowledge.
            }
            RuntimeException injected = (channel.equals("REALTIME") ? realtimeFailure : pushFailure).getAndSet(null);
            if (injected != null) { throw injected; }
        }

        void reset() {
            attempts.clear();
            realtimeFailure.set(null);
            pushFailure.set(null);
            haltAfterRealtime = false;
        }
    }

    static final class RuntimeContext implements AutoCloseable {
        private final HikariDataSource pool;
        private final AnnotationConfigApplicationContext application;

        RuntimeContext(HikariDataSource pool) {
            this.pool = pool;
            application = new AnnotationConfigApplicationContext();
            application.registerBean(DataSource.class, () -> pool);
            application.register(FixtureConfiguration.class);
            application.refresh();
        }

        HikariDataSource pool() { return pool; }
        MessageService messages() { return application.getBean(MessageService.class); }
        SocialService social() { return application.getBean(SocialService.class); }
        MessageOutboxStore store() { return application.getBean(MessageOutboxStore.class); }
        MessageOutboxWorker worker() { return application.getBean(MessageOutboxWorker.class); }
        DataSourceTransactionManager manager() { return application.getBean(DataSourceTransactionManager.class); }
        SimpleMeterRegistry metrics() { return application.getBean(SimpleMeterRegistry.class); }
        Probe probe() { return application.getBean(Probe.class); }
        <T> T mapper(Class<T> type) { return application.getBean(SqlSessionTemplate.class).getMapper(type); }

        @Override public void close() { application.close(); pool.close(); }
    }

    /** A real separate JVM; it dies without close/ack, while its committed claim remains in MySQL. */
    public static final class CrashAfterRealtime {
        public static void main(String[] ignored) {
            try (RuntimeContext child = new RuntimeContext(fixturePool(1))) {
                child.probe().haltAfterRealtime = true;
                child.worker().drainOnce();
                throw new IllegalStateException("No committed realtime delivery reached the child crash gate");
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class FixtureConfiguration {
        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setDefaultStatementTimeout(5);
            for (Class<?> type : List.of(ConversationMapper.class, MessageMapper.class, MessageOutboxMapper.class,
                    UserMapper.class, UserFollowMapper.class, UserBlockMapper.class, UserReportMapper.class,
                    SocialRelationshipLockMapper.class)) { configuration.addMapper(type); }
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setConfiguration(configuration);
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) { return new SqlSessionTemplate(factory); }

        @Bean
        DataSourceTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }

        @Bean
        SocialService socialService(SqlSessionTemplate template) {
            return new SocialService(template.getMapper(UserMapper.class), template.getMapper(UserFollowMapper.class),
                    template.getMapper(UserBlockMapper.class),
                    template.getMapper(SocialRelationshipLockMapper.class));
        }

        @Bean
        MessageOutboxStore outboxStore(SqlSessionTemplate template, DataSourceTransactionManager manager) {
            return new MessageOutboxStore(template.getMapper(MessageOutboxMapper.class), manager);
        }

        @Bean
        MessageService messageService(SqlSessionTemplate template, SocialService social, MessageOutboxStore store) {
            return new MessageService(template.getMapper(ConversationMapper.class), template.getMapper(MessageMapper.class),
                    template.getMapper(UserMapper.class), new PresenceService(new MemoryPresenceStore()), store, social);
        }

        @Bean
        SimpleMeterRegistry metrics() { return new SimpleMeterRegistry(); }

        @Bean
        Probe probe(DataSource source) { return new Probe((HikariDataSource) source); }

        @Bean
        MessageOutboxWorker worker(SqlSessionTemplate template, SocialService social, MessageOutboxStore store,
                                   SimpleMeterRegistry metrics, Probe probe) {
            RealtimeHub realtime = mock(RealtimeHub.class);
            doAnswer(call -> {
                Map<?, ?> payload = call.getArgument(2);
                var message = (MessageDtos.MessageView) payload.get("message");
                probe.record("REALTIME", (String) payload.get("eventId"), message.id());
                return null;
            }).when(realtime).publish(any(Long.class), eq("message"), any());
            PushService push = (receiver, title, body, payload) -> probe.record("PUSH", payload.get("eventId"),
                    Long.parseLong(payload.get("messageId")));
            return new MessageOutboxWorker(store, template.getMapper(MessageMapper.class), template.getMapper(UserMapper.class),
                    social, realtime, push, metrics);
        }
    }
}
