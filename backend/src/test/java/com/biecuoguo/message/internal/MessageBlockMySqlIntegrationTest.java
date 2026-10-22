package com.biecuoguo.message.internal;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.biecuoguo.mapper.SocialRelationshipLockMapper;
import com.biecuoguo.mapper.UserBlockMapper;
import com.biecuoguo.mapper.UserFollowMapper;
import com.biecuoguo.mapper.UserMapper;
import com.biecuoguo.mapper.UserReportMapper;
import com.biecuoguo.message.api.MessageDtos;
import com.biecuoguo.push.api.PushService;
import com.biecuoguo.realtime.LocalRealtimeBroker;
import com.biecuoguo.realtime.MemoryRealtimeStore;
import com.biecuoguo.realtime.RealtimeHub;
import com.biecuoguo.realtime.RealtimeSecurityExecutor;
import com.biecuoguo.security.AuthenticationContext;
import com.biecuoguo.security.AuthenticationStateService;
import com.biecuoguo.security.CurrentUser;
import com.biecuoguo.service.PresenceService;
import com.biecuoguo.service.SocialService;
import com.biecuoguo.social.api.BlockQueryApi;
import com.biecuoguo.state.MemoryPresenceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
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
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real MySQL transactions; no application configuration, user credentials or remote dependencies. */
@EnabledIfEnvironmentVariable(named = "UNIPO_DP03_MESSAGE_MYSQL_INTEGRATION", matches = "1")
@Timeout(30)
class MessageBlockMySqlIntegrationTest {
    private static final AtomicInteger PUBLIC_UID = new AtomicInteger(73_000_000);
    private static AnnotationConfigApplicationContext context;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager transactionManager;
    private static MessageService messages;
    private static MessageOutboxWorker outboxWorker;
    private static SocialService social;
    private static GatedBlocks gatedBlocks;
    private static DeliveryProbe deliveries;
    private static long schoolId;
    private final List<Long> ownedUsers = new ArrayList<>();

    @BeforeAll
    static void connectOnlyToTheNewOwnedFixtureAndMigrate() {
        assertEquals("1", System.getenv("UNIPO_DP02_MYSQL_INTEGRATION"));
        assertEquals("127.0.0.1", System.getenv("UNIPO_DP02_MYSQL_HOST"));
        assertEquals("22306", System.getenv("UNIPO_DP02_MYSQL_PORT"));
        assertEquals("unipo_fixture", System.getenv("UNIPO_DP02_MYSQL_USER"));
        String runId = System.getenv("UNIPO_DP02_MYSQL_RUN_ID");
        assertNotNull(runId, "Start with scripts/integration/message-blocks.sh");
        assertTrue(runId.matches("[0-9a-f]{16}"));
        String schema = System.getenv("UNIPO_DP02_MYSQL_SCHEMA");
        assertEquals("unipo_dp02_" + runId, schema);
        String password = System.getenv("UNIPO_DP02_MYSQL_PASSWORD");
        assertNotNull(password);
        assertTrue(password.matches("[0-9a-f]{48}"));
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl("jdbc:mysql://127.0.0.1:22306/" + schema
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                + "&connectTimeout=2000&socketTimeout=10000");
        source.setUsername("unipo_fixture");
        source.setPassword(password);
        dataSource = source;
        jdbc = new JdbcTemplate(source);
        // No schema/application writes precede all ownership checks.
        assertEquals(schema, jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertEquals(runId, jdbc.queryForObject("SELECT LOWER(HEX(run_id)) FROM dp02_fixture_marker", String.class));
        assertEquals(List.of("dp02_fixture_marker"), jdbc.queryForList("SHOW TABLES", String.class));
        Flyway flyway = Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("0").createSchemas(false).cleanDisabled(true).load();
        flyway.migrate();
        assertEquals(0, flyway.info().pending().length);
        assertTrue(flyway.validateWithResult().validationSuccessful);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history "
                + "WHERE version = '23' AND success = 1", Integer.class));
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> source);
        context.register(FixtureConfiguration.class);
        context.refresh();
        transactionManager = context.getBean(DataSourceTransactionManager.class);
        messages = context.getBean(MessageService.class);
        outboxWorker = context.getBean(MessageOutboxWorker.class);
        social = context.getBean(SocialService.class);
        gatedBlocks = context.getBean(GatedBlocks.class);
        deliveries = context.getBean(DeliveryProbe.class);
        assertTrue(AopUtils.isAopProxy(messages));
        assertTrue(AopUtils.isAopProxy(social));
        String code = "dp03-message-" + UUID.randomUUID();
        jdbc.update("INSERT INTO schools (name, code, created_at, updated_at) VALUES (?, ?, NOW(), NOW())",
                "DP03 message fixture", code);
        Long createdSchool = jdbc.queryForObject("SELECT id FROM schools WHERE code = ?", Long.class, code);
        assertNotNull(createdSchool);
        schoolId = createdSchool;
        System.out.println("DP03 message fixture ownership, full migration chain and real transaction proxies verified");
    }

    @AfterEach
    void removeOnlyThisTestsOwnedRows() {
        gatedBlocks.reset();
        deliveries.forward.set(null);
        for (Long userId : ownedUsers) {
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    @AfterAll
    static void closeOwnedFixture() {
        try {
            if (schoolId != 0) { jdbc.update("DELETE FROM schools WHERE id = ?", schoolId); }
        } finally {
            if (context != null) { context.close(); }
        }
    }

    @Test
    void ownBlockHidesTheConversationAndRejectsHistoryCreateAndSend() {
        assertBlockedMessageBoundary(false);
    }

    @Test
    void peersBlockHidesTheConversationAndRejectsHistoryCreateAndSend() {
        assertBlockedMessageBoundary(true);
    }

    private void assertBlockedMessageBoundary(boolean peerBlocks) {
        CurrentUser viewer = newUser();
        CurrentUser peer = newUser();
        long conversation = seedConversation(viewer, peer);
        seedMessage(conversation, peer, viewer, false);
        assertEquals(1L, messages.unreadCount(viewer));
        assertEquals(1, messages.conversations(viewer).size());
        if (peerBlocks) { social.block(uid(viewer), peer); }
        else { social.block(uid(peer), viewer); }

        assertTrue(messages.conversations(viewer).isEmpty());
        assertEquals(0L, messages.unreadCount(viewer));
        assertThrows(NoSuchElementException.class, () -> messages.messages(conversation, viewer));
        assertThrows(NoSuchElementException.class, () -> messages.create(
                new MessageDtos.CreateConversationRequest(uid(peer), null), viewer));
        assertThrows(NoSuchElementException.class, () -> messages.send(conversation,
                new MessageDtos.SendMessageRequest("must not save"), viewer));
        assertEquals(1, messageCount(conversation));
        assertEquals(1, unreadFacts(conversation, viewer));
        assertEquals(0, deliveries.forConversation(conversation));
    }

    @Test
    void unblockingRestoresHistoryAndUnreadButDoesNotRestoreEitherFollow() {
        CurrentUser viewer = newUser();
        CurrentUser peer = newUser();
        long conversation = seedConversation(viewer, peer);
        seedMessage(conversation, peer, viewer, false);
        social.follow(uid(peer), viewer);
        social.follow(uid(viewer), peer);
        social.block(uid(peer), viewer);
        assertEquals(0, follows(viewer, peer));

        social.unblock(uid(peer), viewer);

        assertEquals(1, messages.conversations(viewer).size());
        assertEquals(1L, messages.unreadCount(viewer));
        assertEquals(1, messages.messages(conversation, viewer).size());
        assertEquals(0L, messages.unreadCount(viewer));
        assertEquals(0, follows(viewer, peer));
    }

    @Test
    void removingOnlyOneDirectionDoesNotLiftTheOtherSidesBlock() {
        CurrentUser viewer = newUser();
        CurrentUser peer = newUser();
        long conversation = seedConversation(viewer, peer);
        seedMessage(conversation, peer, viewer, false);
        social.block(uid(peer), viewer);
        social.block(uid(viewer), peer);
        social.unblock(uid(peer), viewer);

        assertTrue(messages.conversations(viewer).isEmpty());
        assertEquals(0L, messages.unreadCount(viewer));
        assertThrows(NoSuchElementException.class, () -> messages.messages(conversation, viewer));

        social.unblock(uid(viewer), peer);
        assertEquals(1, messages.conversations(viewer).size());
        assertEquals(1L, messages.unreadCount(viewer));
    }

    @Test
    void concurrentDuplicateBlocksAreIdempotentAndRemoveBothFollows() {
        CurrentUser viewer = newUser();
        CurrentUser peer = newUser();
        social.follow(uid(peer), viewer);
        social.follow(uid(viewer), peer);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                tasks.add(workers.submit(() -> {
                    ready.countDown();
                    await(start);
                    assertTrue(social.block(uid(peer), viewer).blocked());
                }));
            }
            await(ready);
            start.countDown();
            tasks.forEach(MessageBlockMySqlIntegrationTest::result);
        } finally {
            start.countDown();
            stop(workers);
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_blocks WHERE blocker_id = ? AND blocked_id = ?",
                Integer.class, viewer.id(), peer.id()));
        assertEquals(0, follows(viewer, peer));
    }

    @Test
    void sendAlreadyWaitingForThePairRejectsAfterTheBlockCommits() {
        assertBlockWinsAgainstWaitingAction(false);
    }

    @Test
    void historyAlreadyWaitingForThePairRejectsWithoutMarkingReadAfterBlockCommit() {
        assertBlockWinsAgainstWaitingAction(true);
    }

    private void assertBlockWinsAgainstWaitingAction(boolean readHistory) {
        CurrentUser viewer = newUser();
        CurrentUser peer = newUser();
        long conversation = seedConversation(viewer, peer);
        seedMessage(conversation, peer, viewer, false);
        PairGate gate = new PairGate();
        gatedBlocks.pairGate.set(gate);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        AtomicReference<Future<?>> task = new AtomicReference<>();
        try {
            transaction(TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(status -> {
                social.block(uid(peer), viewer); // Owns the pair lock until this transaction commits.
                task.set(worker.submit(() -> assertThrows(NoSuchElementException.class, () -> {
                    if (readHistory) { messages.messages(conversation, viewer); }
                    else { messages.send(conversation, new MessageDtos.SendMessageRequest("blocked race"), viewer); }
                })));
                await(gate.entered);
                assertFalse(awaitBriefly(gate.acquired), "The message action must wait for the held DB relationship lock");
                assertFalse(task.get().isDone());
            });
            result(task.get());
            assertEquals(0L, gate.acquired.getCount(), "The waiting lock must proceed after commit");
            assertEquals(1, messageCount(conversation));
            assertEquals(1, unreadFacts(conversation, viewer));
            assertEquals(0, deliveries.forConversation(conversation));
        } finally {
            stop(worker);
        }
    }

    @Test
    void messageThatOwnsThePairCommitsBeforeTheQueuedBlockAndThenBecomesHidden() {
        CurrentUser sender = newUser();
        CurrentUser receiver = newUser();
        long conversation = seedConversation(sender, receiver);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch blocked = new CountDownLatch(1);
        AtomicReference<Future<?>> task = new AtomicReference<>();
        try {
            transaction(TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(status -> {
                messages.send(conversation, new MessageDtos.SendMessageRequest("committed first"), sender);
                assertEquals(1, messageCount(conversation));
                assertEquals(0, deliveries.forConversation(conversation));
                task.set(worker.submit(() -> {
                    started.countDown();
                    social.block(uid(sender), receiver);
                    blocked.countDown();
                }));
                await(started);
                assertFalse(awaitBriefly(blocked), "Block must not commit while the sender still owns the pair");
            });
            result(task.get());
            assertEquals(1, messageCount(conversation));
            assertTrue(messages.conversations(receiver).isEmpty());
            assertEquals(0L, messages.unreadCount(receiver));
        } finally {
            stop(worker);
        }
    }

    @Test
    void rolledBackSendLeavesNoMessagePreviewOutboxEventOrPush() {
        CurrentUser sender = newUser();
        CurrentUser receiver = newUser();
        long conversation = seedConversation(sender, receiver);
        transaction(TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(status -> {
            messages.send(conversation, new MessageDtos.SendMessageRequest("rollback"), sender);
            assertEquals(1, messageCount(conversation));
            assertEquals(0, deliveries.forConversation(conversation));
            status.setRollbackOnly();
        });
        assertEquals(0, messageCount(conversation));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM message_delivery_outbox", Integer.class));
        assertEquals(0, outboxWorker.drainOnce());
        assertEquals("", messages.conversations(receiver).getFirst().lastMessage());
        assertEquals(0, deliveries.forConversation(conversation));
        assertEquals(0, deliveries.pushForConversation(conversation));
    }

    @Test
    void workerPublicationCanReadTheMessageFromAnIndependentCommittedConnection() {
        CurrentUser sender = newUser();
        CurrentUser receiver = newUser();
        long conversation = seedConversation(sender, receiver);
        transaction(TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(status -> {
            messages.send(conversation, new MessageDtos.SendMessageRequest("commit before notify"), sender);
            assertEquals(0, deliveries.forConversation(conversation));
            assertEquals(0, independentMessageCount(conversation));
        });
        assertEquals(0, deliveries.forConversation(conversation), "Message commit only makes its outbox intents available");
        assertEquals(2, outboxWorker.drainOnce());
        assertEquals(1, deliveries.forConversation(conversation));
        assertEquals(1, deliveries.committedCountAtPublication(conversation));
    }

    @Test
    void aBlockCommittedDuringTheFirstWorkerSinkStopsTheNextConnection() {
        CurrentUser sender = newUser();
        CurrentUser receiver = newUser();
        long conversation = seedConversation(sender, receiver);
        AuthenticationStateService authentication = mock(AuthenticationStateService.class);
        when(authentication.isValid(any())).thenReturn(true);
        RealtimeSecurityExecutor security = new RealtimeSecurityExecutor(authentication);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        RealtimeHub hub = new RealtimeHub(new LocalRealtimeBroker(), new MemoryRealtimeStore(),
                new ObjectMapper().findAndRegisterModules(), metrics, security, social);
        ExecutorService blockWriter = Executors.newSingleThreadExecutor();
        AtomicInteger acceptedFrames = new AtomicInteger();
        AtomicReference<Throwable> sinkFailure = new AtomicReference<>();
        Consumer<String> sink = frame -> {
            if (acceptedFrames.incrementAndGet() == 1) {
                try {
                    result(blockWriter.submit(() -> social.block(uid(sender), receiver)));
                } catch (RuntimeException | Error failure) {
                    sinkFailure.set(failure);
                    throw failure;
                }
            }
        };
        var identity = new AuthenticationContext(receiver, Instant.now().plusSeconds(60), null);
        var first = hub.register(identity, sink, () -> {});
        var second = hub.register(identity, sink, () -> {});
        deliveries.forward.set(hub);
        try {
            messages.send(conversation, new MessageDtos.SendMessageRequest("check every local connection"), sender);
            assertEquals(0, acceptedFrames.get());
            assertEquals(2, outboxWorker.drainOnce());

            if (sinkFailure.get() != null) { throw new AssertionError("Block must commit within the first sink", sinkFailure.get()); }
            assertEquals(1, acceptedFrames.get(), "The second connection must recheck the newly committed relationship");
            assertEquals(1, messageCount(conversation));
            assertTrue(social.isBlockedEitherWay(sender.id(), receiver.id()));
        } finally {
            deliveries.forward.set(null);
            first.close();
            second.close();
            stop(blockWriter);
            security.destroy();
            metrics.close();
        }
    }

    @Test
    void conversationFinalCheckSeesBlockCommittedAfterCandidatesEvenInsideAnOldSnapshot() {
        assertFinalAuthoritySeesConcurrentBlock(false);
    }

    @Test
    void unreadFinalCheckSeesBlockCommittedAfterCandidatesEvenInsideAnOldSnapshot() {
        assertFinalAuthoritySeesConcurrentBlock(true);
    }

    private void assertFinalAuthoritySeesConcurrentBlock(boolean unreadOnly) {
        CurrentUser viewer = newUser();
        CurrentUser peer = newUser();
        long conversation = seedConversation(viewer, peer);
        seedMessage(conversation, peer, viewer, false);
        CandidateGate gate = new CandidateGate();
        gatedBlocks.candidateGate.set(gate);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<?> task = worker.submit(() -> transaction(TransactionDefinition.ISOLATION_REPEATABLE_READ)
                .executeWithoutResult(status -> {
                    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_blocks WHERE blocker_id = ?",
                            Integer.class, viewer.id())); // Establish the outer stale RR snapshot.
                    if (unreadOnly) { assertEquals(0L, messages.unreadCount(viewer)); }
                    else { assertTrue(messages.conversations(viewer).isEmpty()); }
                    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_blocks WHERE blocker_id = ?",
                            Integer.class, viewer.id()), "The caller's old snapshot must really remain stale");
                }));
        try {
            await(gate.candidatesRead);
            assertTrue(social.block(uid(peer), viewer).blocked());
            gate.proceed.countDown();
            result(task);
            assertTrue(gate.lookups.get() >= 2, "The result requires a final authoritative batch query");
            assertEquals(1, unreadFacts(conversation, viewer), "Filtering must not silently mark messages read");
        } finally {
            gate.proceed.countDown();
            stop(worker);
        }
    }

    @Test
    void unreadAggregationOnlyCountsUnreadReceivedMessagesFromRequestedCandidateConversations() {
        CurrentUser viewer = newUser();
        CurrentUser firstPeer = newUser();
        CurrentUser otherPeer = newUser();
        long first = seedConversation(viewer, firstPeer);
        long other = seedConversation(viewer, otherPeer);
        seedMessage(first, firstPeer, viewer, false);
        seedMessage(first, firstPeer, viewer, true);
        seedMessage(first, viewer, firstPeer, false);
        seedMessage(other, otherPeer, viewer, false);
        seedMessage(other, otherPeer, viewer, false);
        MessageMapper mapper = context.getBean(SqlSessionTemplate.class).getMapper(MessageMapper.class);

        assertEquals(List.of(new MessageMapper.UnreadCount(first, 1L)), mapper.selectUnreadCounts(viewer.id(), List.of(first)));
        assertEquals(3L, messages.unreadCount(viewer));
        social.block(uid(otherPeer), viewer);
        assertEquals(1L, messages.unreadCount(viewer));
        assertEquals(1, messages.conversations(viewer).size());
        assertEquals(1L, messages.conversations(viewer).getFirst().unreadCount());
    }

    @Test
    void anUnrelatedAccountCannotReadOrSendThroughAnotherPairsConversation() {
        CurrentUser sender = newUser();
        CurrentUser receiver = newUser();
        CurrentUser outsider = newUser();
        long conversation = seedConversation(sender, receiver);
        seedMessage(conversation, sender, receiver, false);

        assertThrows(NoSuchElementException.class, () -> messages.messages(conversation, outsider));
        assertThrows(NoSuchElementException.class, () -> messages.send(conversation,
                new MessageDtos.SendMessageRequest("not a participant"), outsider));
        assertTrue(messages.conversations(outsider).isEmpty());
        assertEquals(0L, messages.unreadCount(outsider));
        assertEquals(1, messageCount(conversation));
        assertEquals(1, unreadFacts(conversation, receiver));
    }

    private CurrentUser newUser() {
        String account = "message-" + UUID.randomUUID() + "@example.invalid";
        jdbc.update("INSERT INTO users (public_uid, email, password_hash, nickname, school_id, role, status, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'USER', 'active', NOW(), NOW())",
                Integer.toString(PUBLIC_UID.getAndIncrement()), account, UUID.randomUUID().toString(),
                "msg-" + UUID.randomUUID(), schoolId);
        Long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, account);
        assertNotNull(id);
        ownedUsers.add(id);
        return new CurrentUser(id, account, "USER");
    }

    private long seedConversation(CurrentUser first, CurrentUser second) {
        long low = Math.min(first.id(), second.id());
        long high = Math.max(first.id(), second.id());
        jdbc.update("INSERT INTO conversations (user_one_id, user_two_id, last_message_at, created_at, updated_at) "
                + "VALUES (?, ?, NOW(), NOW(), NOW())", low, high);
        Long id = jdbc.queryForObject("SELECT id FROM conversations WHERE user_one_id = ? AND user_two_id = ?",
                Long.class, low, high);
        assertNotNull(id);
        return id;
    }

    private void seedMessage(long conversation, CurrentUser sender, CurrentUser receiver, boolean read) {
        jdbc.update("INSERT INTO messages (conversation_id, sender_id, receiver_id, content, is_read, created_at) "
                + "VALUES (?, ?, ?, 'fixture message', ?, NOW())", conversation, sender.id(), receiver.id(), read);
    }

    private String uid(CurrentUser user) {
        return jdbc.queryForObject("SELECT public_uid FROM users WHERE id = ?", String.class, user.id());
    }

    private int follows(CurrentUser first, CurrentUser second) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_follows WHERE "
                        + "(follower_id = ? AND following_id = ?) OR (follower_id = ? AND following_id = ?)",
                Integer.class, first.id(), second.id(), second.id(), first.id());
    }

    private static int messageCount(long conversation) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM messages WHERE conversation_id = ?", Integer.class, conversation);
    }

    private int unreadFacts(long conversation, CurrentUser receiver) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM messages WHERE conversation_id = ? AND receiver_id = ? AND is_read = false",
                Integer.class, conversation, receiver.id());
    }

    private static int independentMessageCount(long conversation) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("SELECT COUNT(*) FROM messages WHERE conversation_id = ?")) {
            query.setLong(1, conversation);
            try (ResultSet rows = query.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("Independent message query failed", failure);
        }
    }

    private static TransactionTemplate transaction(int isolation) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(isolation);
        template.setTimeout(15);
        return template;
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS), "Fixture gate did not progress"); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Fixture gate interrupted", interrupted);
        }
    }

    private static boolean awaitBriefly(CountDownLatch latch) {
        try { return latch.await(150, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Fixture lock observation interrupted", interrupted);
        }
    }

    private static void result(Future<?> task) {
        try { task.get(10, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Fixture result interrupted", interrupted);
        } catch (ExecutionException failure) {
            throw new IllegalStateException("Fixture action failed", failure.getCause());
        } catch (java.util.concurrent.TimeoutException timeout) {
            throw new IllegalStateException("Fixture action timed out", timeout);
        }
    }

    private static void stop(ExecutorService workers) {
        workers.shutdownNow();
        try { assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "Fixture workers must stop before cleanup"); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Fixture worker shutdown interrupted", interrupted);
        }
    }

    static final class PairGate {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch acquired = new CountDownLatch(1);
    }

    static final class CandidateGate {
        final CountDownLatch candidatesRead = new CountDownLatch(1);
        final CountDownLatch proceed = new CountDownLatch(1);
        final AtomicInteger lookups = new AtomicInteger();
    }

    /** Only adds scheduling gates; every relationship decision and DB lock delegates to production code. */
    static final class GatedBlocks implements BlockQueryApi {
        private final SocialService delegate;
        final AtomicReference<PairGate> pairGate = new AtomicReference<>();
        final AtomicReference<CandidateGate> candidateGate = new AtomicReference<>();

        GatedBlocks(SocialService delegate) { this.delegate = delegate; }

        @Override
        public void lockPair(Long firstUserId, Long secondUserId) {
            PairGate gate = pairGate.get();
            if (gate != null) { gate.entered.countDown(); }
            delegate.lockPair(firstUserId, secondUserId);
            if (gate != null) { gate.acquired.countDown(); }
        }

        @Override
        public Set<Long> blockedUserIds(Long viewerId) {
            Set<Long> result = delegate.blockedUserIds(viewerId);
            CandidateGate gate = candidateGate.get();
            if (gate != null && gate.lookups.incrementAndGet() == 1) {
                assertTrue(result.isEmpty(), "Candidate gate must begin before the block exists");
                gate.candidatesRead.countDown();
                await(gate.proceed);
            }
            return result;
        }

        @Override
        public boolean isBlockedEitherWay(Long firstUserId, Long secondUserId) {
            return delegate.isBlockedEitherWay(firstUserId, secondUserId);
        }

        @Override
        public boolean isBlockedInLockedPair(Long firstUserId, Long secondUserId) {
            return delegate.isBlockedInLockedPair(firstUserId, secondUserId);
        }

        void reset() {
            CandidateGate old = candidateGate.getAndSet(null);
            if (old != null) { old.proceed.countDown(); }
            pairGate.set(null);
        }
    }

    static final class DeliveryProbe {
        record Publication(long conversation, int committedMessages) {}
        final ConcurrentLinkedQueue<Publication> realtime = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<Long> push = new ConcurrentLinkedQueue<>();
        final AtomicReference<RealtimeHub> forward = new AtomicReference<>();

        int forConversation(long conversation) {
            return (int) realtime.stream().filter(item -> item.conversation() == conversation).count();
        }

        int pushForConversation(long conversation) {
            return (int) push.stream().filter(item -> item == conversation).count();
        }

        int committedCountAtPublication(long conversation) {
            return realtime.stream().filter(item -> item.conversation() == conversation).findFirst().orElseThrow().committedMessages();
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
            for (Class<?> mapper : List.of(ConversationMapper.class, MessageMapper.class, MessageOutboxMapper.class, UserMapper.class,
                    UserFollowMapper.class, UserBlockMapper.class, UserReportMapper.class, SocialRelationshipLockMapper.class)) {
                configuration.addMapper(mapper);
            }
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
                    template.getMapper(UserBlockMapper.class), template.getMapper(UserReportMapper.class),
                    template.getMapper(SocialRelationshipLockMapper.class));
        }

        @Bean
        GatedBlocks gatedBlocks(SocialService service) { return new GatedBlocks(service); }

        @Bean
        DeliveryProbe deliveryProbe() { return new DeliveryProbe(); }

        @Bean
        MessageOutboxStore outboxStore(SqlSessionTemplate template, DataSourceTransactionManager manager) {
            return new MessageOutboxStore(template.getMapper(MessageOutboxMapper.class), manager);
        }

        @Bean
        MessageService messageService(SqlSessionTemplate template, GatedBlocks blocks, MessageOutboxStore outbox) {
            return new MessageService(template.getMapper(ConversationMapper.class), template.getMapper(MessageMapper.class),
                    template.getMapper(UserMapper.class), new PresenceService(new MemoryPresenceStore()), outbox, blocks);
        }

        @Bean
        MessageOutboxWorker outboxWorker(SqlSessionTemplate template, GatedBlocks blocks,
                                        DeliveryProbe probe, MessageOutboxStore outbox) {
            RealtimeHub realtime = mock(RealtimeHub.class);
            doAnswer(invocation -> {
                java.util.Map<?, ?> payload = invocation.getArgument(2);
                long conversation = ((Number) payload.get("conversationId")).longValue();
                probe.realtime.add(new DeliveryProbe.Publication(conversation, independentMessageCount(conversation)));
                RealtimeHub forward = probe.forward.get();
                if (forward != null) { forward.publish(invocation.getArgument(0), "message", payload); }
                return null;
            }).when(realtime).publish(any(Long.class), eq("message"), any());
            PushService push = (receiver, title, body, payload) -> probe.push.add(Long.parseLong(payload.get("conversationId")));
            return new MessageOutboxWorker(outbox, template.getMapper(MessageMapper.class), template.getMapper(UserMapper.class),
                    blocks, realtime, push, new SimpleMeterRegistry());
        }
    }
}
