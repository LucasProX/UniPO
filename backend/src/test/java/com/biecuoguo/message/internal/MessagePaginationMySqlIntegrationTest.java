package com.biecuoguo.message.internal;

import com.biecuoguo.config.AppTime;
import com.biecuoguo.error.StateConflictException;
import com.biecuoguo.message.api.MessageDtos;
import com.biecuoguo.security.CurrentUser;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** DP15 against an owned empty MySQL fixture; uses the production transaction-advised services. */
@EnabledIfEnvironmentVariable(named = "UNIPO_DP15_MYSQL_INTEGRATION", matches = "1")
@Timeout(40)
class MessagePaginationMySqlIntegrationTest {
    private static final AtomicInteger PUBLIC_UID = new AtomicInteger(78_000_000);
    private static MessageOutboxMySqlIntegrationTest.RuntimeContext runtime;
    private static JdbcTemplate jdbc;
    private static long schoolId;
    private final List<Long> ownedUsers = new ArrayList<>();

    @BeforeAll
    static void connectToOwnedFixtureAndMigrate() {
        assertEquals("1", System.getenv("UNIPO_DP02_MYSQL_INTEGRATION"));
        assertEquals("127.0.0.1", System.getenv("UNIPO_DP02_MYSQL_HOST"));
        assertEquals("22306", System.getenv("UNIPO_DP02_MYSQL_PORT"));
        assertEquals("unipo_fixture", System.getenv("UNIPO_DP02_MYSQL_USER"));
        String run = System.getenv("UNIPO_DP02_MYSQL_RUN_ID");
        assertNotNull(run);
        assertTrue(run.matches("[0-9a-f]{16}"));
        String schema = System.getenv("UNIPO_DP02_MYSQL_SCHEMA");
        assertEquals("unipo_dp02_" + run, schema);
        String password = System.getenv("UNIPO_DP02_MYSQL_PASSWORD");
        assertNotNull(password);
        assertTrue(password.matches("[0-9a-f]{48}"));
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://127.0.0.1:22306/" + schema
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&socketTimeout=10000&connectTimeout=2000");
        config.setUsername("unipo_fixture");
        config.setPassword(password);
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(2000);
        HikariDataSource pool = new HikariDataSource(config);
        jdbc = new JdbcTemplate(pool);
        assertEquals(schema, jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertEquals(run, jdbc.queryForObject("SELECT LOWER(HEX(run_id)) FROM dp02_fixture_marker", String.class));
        assertEquals(List.of("dp02_fixture_marker"), jdbc.queryForList("SHOW TABLES", String.class));
        var flyway = Flyway.configure().dataSource(pool).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("0").cleanDisabled(true).createSchemas(false).load();
        flyway.migrate();
        assertEquals(0, flyway.info().pending().length);
        assertTrue(flyway.validateWithResult().validationSuccessful);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='25' AND success=1", Integer.class));
        System.out.println("DP15 message index layout: " + jdbc.queryForList(
                "SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS indexed_columns "
                        + "FROM information_schema.statistics WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='messages' GROUP BY INDEX_NAME"));
        runtime = new MessageOutboxMySqlIntegrationTest.RuntimeContext(pool);
        String code = "dp15-" + UUID.randomUUID();
        jdbc.update("INSERT INTO schools(name,code,created_at,updated_at) VALUES(?,?,NOW(),NOW())", "DP15 fixture", code);
        schoolId = jdbc.queryForObject("SELECT id FROM schools WHERE code=?", Long.class, code);
    }

    @AfterEach
    void removeOnlyOwnedRows() {
        for (Long id : ownedUsers) { jdbc.update("DELETE FROM users WHERE id=?", id); }
        runtime.probe().reset();
    }

    @AfterAll
    static void closeOwnedFixture() {
        try { if (schoolId != 0) { jdbc.update("DELETE FROM schools WHERE id=?", schoolId); } }
        finally { if (runtime != null) { runtime.close(); } }
    }

    @Test
    void historyUsesExclusiveIdPagesAndOnlyMarksTheReturnedIncomingRowsRead() {
        CurrentUser viewer = user();
        CurrentUser peer = user();
        long conversation = conversation(viewer, peer, AppTime.nowLocal());
        List<Long> expected = new ArrayList<>();
        for (int n = 0; n < 37; n++) { expected.add(seed(conversation, peer, viewer, "incoming-" + n)); }
        expected.sort(Comparator.reverseOrder());
        var first = runtime.messages().messagePage(conversation, null, 10, viewer);
        assertEquals(expected.subList(0, 10), first.items().stream().map(MessageDtos.MessageView::id).toList());
        assertTrue(first.hasMore());
        assertEquals(27, unread(conversation, viewer));
        assertTrue(first.items().stream().allMatch(item -> Boolean.TRUE.equals(item.read())));
        List<Long> actual = new ArrayList<>(first.items().stream().map(MessageDtos.MessageView::id).toList());
        String cursor = first.nextCursor();
        while (cursor != null) {
            var page = runtime.messages().messagePage(conversation, cursor, 10, viewer);
            actual.addAll(page.items().stream().map(MessageDtos.MessageView::id).toList());
            cursor = page.nextCursor();
            assertTrue(actual.size() <= 37, "Exclusive cursors must terminate without repeated rows");
        }
        assertEquals(expected, actual);
        assertEquals(actual.size(), new HashSet<>(actual).size());
        assertEquals(0, unread(conversation, viewer));
    }

    @Test
    void aNewMessageBetweenHistoryPagesDoesNotChangeTheOlderCursorWindow() {
        CurrentUser viewer = user();
        CurrentUser peer = user();
        long conversation = conversation(viewer, peer, AppTime.nowLocal());
        List<Long> original = new ArrayList<>();
        for (int n = 0; n < 12; n++) { original.add(seed(conversation, peer, viewer, "old-" + n)); }
        var first = runtime.messages().messagePage(conversation, null, 5, viewer);
        long fresh = seed(conversation, peer, viewer, "new while paging");
        var older = runtime.messages().messagePage(conversation, first.nextCursor(), 50, viewer);
        assertFalse(older.items().stream().anyMatch(item -> item.id() == fresh));
        assertEquals(7, older.items().size());
        assertEquals(1, unread(conversation, viewer), "The unseen newly arrived message must remain unread");
    }

    @Test
    void forwardRecoveryFillsMoreThanOnePageAndFreezesItsUpperBound() {
        CurrentUser viewer = user();
        CurrentUser peer = user();
        long conversation = conversation(viewer, peer, AppTime.nowLocal());
        long known = seed(conversation, peer, viewer, "already known");
        List<Long> expected = new ArrayList<>();
        for (int n = 0; n < 127; n++) { expected.add(seed(conversation, peer, viewer, "missed-" + n)); }
        var first = runtime.messages().messagePage(conversation, null, known, 40, viewer);
        assertEquals(expected.subList(0, 40), first.items().stream().map(MessageDtos.MessageView::id).toList());
        assertTrue(first.hasMore());
        long duringRecovery = seed(conversation, peer, viewer, "arrived during recovery");
        List<Long> recovered = new ArrayList<>(first.items().stream().map(MessageDtos.MessageView::id).toList());
        String cursor = first.nextCursor();
        while (cursor != null) {
            var page = runtime.messages().messagePage(conversation, cursor, null, 40, viewer);
            recovered.addAll(page.items().stream().map(MessageDtos.MessageView::id).toList());
            cursor = page.nextCursor();
            assertTrue(recovered.size() <= expected.size());
        }
        assertEquals(expected, recovered);
        assertEquals(2, unread(conversation, viewer), "Known and post-snapshot rows were not returned");
        var nextPass = runtime.messages().messagePage(conversation, null, expected.getLast(), 40, viewer);
        assertEquals(List.of(duringRecovery), nextPass.items().stream().map(MessageDtos.MessageView::id).toList());
        assertFalse(nextPass.hasMore());
        assertThrows(IllegalArgumentException.class,
                () -> runtime.messages().messagePage(conversation, first.nextCursor(), known, 40, viewer));
        assertThrows(IllegalArgumentException.class,
                () -> runtime.messages().messagePage(conversation, null, -1L, 40, viewer));
        assertTrue(runtime.messages().messagePage(conversation, null, Long.MAX_VALUE, 40, viewer).items().isEmpty());
    }

    @Test
    void conversationPagesCoverBothParticipantIndexesWithTiesAndOneLatestPreviewPerThread() {
        CurrentUser olderPeer = user();
        CurrentUser viewer = user();
        List<Long> expected = new ArrayList<>();
        LocalDateTime tie = AppTime.nowLocal().truncatedTo(ChronoUnit.SECONDS);
        for (int n = 0; n < 22; n++) {
            CurrentUser peer = n == 0 ? olderPeer : user();
            long conversation = conversation(viewer, peer, tie);
            for (int m = 0; m < 3; m++) { seed(conversation, peer, viewer, "preview-" + m); }
            expected.add(conversation);
        }
        expected.sort(Comparator.reverseOrder());
        List<Long> actual = new ArrayList<>();
        String cursor = null;
        do {
            var page = runtime.messages().conversationPage(cursor, 6, viewer);
            assertTrue(page.items().size() <= 6);
            for (var item : page.items()) {
                assertEquals("preview-2", item.lastMessage());
                assertEquals(3L, item.unreadCount());
                actual.add(item.id());
            }
            cursor = page.nextCursor();
            assertTrue(actual.size() <= 22);
        } while (cursor != null);
        assertEquals(expected, actual);
        assertEquals(66L, runtime.messages().unreadCount(viewer));
    }

    @Test
    void emptyThreadsUseCreationTimeForTheirCursorAndPreview() {
        CurrentUser viewer = user();
        CurrentUser first = user();
        CurrentUser second = user();
        LocalDateTime time = AppTime.nowLocal().truncatedTo(ChronoUnit.SECONDS);
        long older = conversation(viewer, first, time);
        long newer = conversation(viewer, second, time);
        jdbc.update("UPDATE conversations SET last_message_at=NULL WHERE id IN (?,?)", older, newer);
        var page = runtime.messages().conversationPage(null, 1, viewer);
        assertEquals(newer, page.items().getFirst().id());
        assertEquals("", page.items().getFirst().lastMessage());
        assertEquals(0L, page.items().getFirst().unreadCount());
        assertEquals(older, runtime.messages().conversationPage(page.nextCursor(), 1, viewer).items().getFirst().id());
    }

    @Test
    void cursorsRejectAnotherOwnerOrConversationAndInvalidPageSizes() {
        CurrentUser viewer = user();
        CurrentUser peer = user();
        CurrentUser third = user();
        long first = conversation(viewer, peer, AppTime.nowLocal());
        long second = conversation(viewer, third, AppTime.nowLocal());
        for (int n = 0; n < 3; n++) { seed(first, peer, viewer, "fixture"); }
        var page = runtime.messages().messagePage(first, null, 1, viewer);
        assertThrows(IllegalArgumentException.class, () -> runtime.messages().messagePage(first, page.nextCursor(), 1, peer));
        assertThrows(IllegalArgumentException.class, () -> runtime.messages().messagePage(second, page.nextCursor(), 1, viewer));
        assertThrows(IllegalArgumentException.class, () -> runtime.messages().messagePage(first, "not-a-cursor", 1, viewer));
        assertThrows(IllegalArgumentException.class, () -> runtime.messages().conversationPage("not-a-cursor", 1, viewer));
        assertThrows(IllegalArgumentException.class, () -> runtime.messages().messagePage(first, null, 0, viewer));
        assertThrows(IllegalArgumentException.class, () -> runtime.messages().conversationPage(null, -1, viewer));
        assertEquals(50, runtime.messages().messagePage(first, null, 500, viewer).limit());
    }

    @Test
    void aForeignConversationAndABlockedPeerCannotBeLoadedOrMarkedRead() {
        CurrentUser viewer = user();
        CurrentUser peer = user();
        CurrentUser outsider = user();
        long conversation = conversation(viewer, peer, AppTime.nowLocal());
        seed(conversation, peer, viewer, "private");
        assertThrows(NoSuchElementException.class, () -> runtime.messages().messagePage(conversation, null, 10, outsider));
        assertThrows(NoSuchElementException.class, () -> runtime.messages().conversation(conversation, outsider));
        runtime.social().block(uid(viewer), peer);
        assertThrows(NoSuchElementException.class, () -> runtime.messages().messagePage(conversation, null, 10, viewer));
        assertThrows(NoSuchElementException.class, () -> runtime.messages().conversation(conversation, viewer));
        assertTrue(runtime.messages().conversationPage(null, 20, viewer).items().isEmpty());
        assertEquals(1, unread(conversation, viewer));
    }

    @Test
    void preciseConversationLookupPreservesUnreadAndFindsAThreadOutsideTheFirstPage() {
        CurrentUser viewer = user();
        CurrentUser peer = user();
        long target = conversation(viewer, peer, AppTime.nowLocal().minusDays(1));
        seed(target, peer, viewer, "target");
        for (int n = 0; n < 5; n++) { conversation(viewer, user(), AppTime.nowLocal()); }
        assertFalse(runtime.messages().conversationPage(null, 2, viewer).items().stream().anyMatch(item -> item.id() == target));
        assertEquals(target, runtime.messages().conversation(target, viewer).id());
        assertEquals(1, unread(target, viewer));
    }

    @Test
    void oneClientIntentReturnsItsOriginalMessageAndDoesNotCreateAnotherNotificationOrActivity() {
        CurrentUser sender = user();
        CurrentUser peer = user();
        long conversation = conversation(sender, peer, AppTime.nowLocal());
        var request = new MessageDtos.SendMessageRequest("same content", "intent-a");
        var original = runtime.messages().send(conversation, request, sender);
        LocalDateTime timestamp = jdbc.queryForObject("SELECT updated_at FROM conversations WHERE id=?", LocalDateTime.class, conversation);
        var repeated = runtime.messages().send(conversation, request, sender);
        assertEquals(original, repeated);
        assertEquals("intent-a", repeated.clientMessageId());
        assertEquals(1, count("SELECT COUNT(*) FROM messages WHERE conversation_id=?", conversation));
        assertEquals(2, count("SELECT COUNT(*) FROM message_delivery_outbox WHERE message_id=?", original.id()));
        assertEquals(timestamp, jdbc.queryForObject("SELECT updated_at FROM conversations WHERE id=?", LocalDateTime.class, conversation));
        assertThrows(StateConflictException.class, () -> runtime.messages().send(conversation,
                new MessageDtos.SendMessageRequest("changed", "intent-a"), sender));
        assertEquals(1, count("SELECT COUNT(*) FROM messages WHERE conversation_id=?", conversation));
    }

    @Test
    void concurrentRetriesOfOneIntentConvergeToOneMessageAndTwoDeliveryRows() throws Exception {
        CurrentUser sender = user();
        CurrentUser peer = user();
        long conversation = conversation(sender, peer, AppTime.nowLocal());
        var request = new MessageDtos.SendMessageRequest("concurrent", "intent-parallel");
        List<MessageDtos.MessageView> results = together(
                () -> runtime.messages().send(conversation, request, sender),
                () -> runtime.messages().send(conversation, request, sender));
        assertEquals(results.getFirst().id(), results.getLast().id());
        assertEquals(1, count("SELECT COUNT(*) FROM messages WHERE conversation_id=?", conversation));
        assertEquals(2, count("SELECT COUNT(*) FROM message_delivery_outbox WHERE message_id=?", results.getFirst().id()));
    }

    @Test
    void aKeyRacingAcrossTwoConversationsHasOneWinnerAndOneStableConflict() throws Exception {
        CurrentUser sender = user();
        long first = conversation(sender, user(), AppTime.nowLocal());
        long second = conversation(sender, user(), AppTime.nowLocal());
        var request = new MessageDtos.SendMessageRequest("cross thread", "global-key");
        List<String> outcomes = together(() -> sendOutcome(first, request, sender), () -> sendOutcome(second, request, sender));
        assertEquals(1, outcomes.stream().filter("saved"::equals).count());
        assertEquals(1, outcomes.stream().filter("conflict"::equals).count());
        assertEquals(1, count("SELECT COUNT(*) FROM messages WHERE sender_id=?", sender.id()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM message_delivery_outbox o JOIN messages m ON m.id=o.message_id WHERE m.sender_id=?",
                Integer.class, sender.id()));
    }

    @Test
    void rollbackOfAnIntentAllowsTheSameKeyToBeSavedLaterAndLegacyRequestsRemainIndependent() {
        CurrentUser sender = user();
        long conversation = conversation(sender, user(), AppTime.nowLocal());
        var request = new MessageDtos.SendMessageRequest("retry after rollback", "rolled-back");
        new TransactionTemplate(runtime.manager()).executeWithoutResult(status -> {
            runtime.messages().send(conversation, request, sender);
            status.setRollbackOnly();
        });
        assertEquals(0, count("SELECT COUNT(*) FROM messages WHERE conversation_id=?", conversation));
        runtime.messages().send(conversation, request, sender);
        runtime.messages().send(conversation, new MessageDtos.SendMessageRequest("legacy"), sender);
        runtime.messages().send(conversation, new MessageDtos.SendMessageRequest("legacy"), sender);
        assertEquals(3, count("SELECT COUNT(*) FROM messages WHERE conversation_id=?", conversation));
    }

    @Test
    void messageAndConversationTimesFollowAppTimeWhenJvmDefaultIsDifferent() {
        CurrentUser sender = user();
        CurrentUser peer = user();
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            LocalDateTime before = AppTime.nowLocal().minusSeconds(2);
            long id = runtime.messages().create(new MessageDtos.CreateConversationRequest(uid(peer), null), sender).id();
            var sent = runtime.messages().send(id, new MessageDtos.SendMessageRequest("time", "app-time"), sender);
            assertTrue(sent.createdAt().toLocalDateTime().isAfter(before));
            assertTrue(sent.createdAt().toLocalDateTime().isBefore(AppTime.nowLocal().plusSeconds(2)));
            assertEquals(AppTime.zone().getRules().getOffset(sent.createdAt().toInstant()), sent.createdAt().getOffset());
        } finally { TimeZone.setDefault(previous); }
    }

    private String sendOutcome(long id, MessageDtos.SendMessageRequest request, CurrentUser sender) {
        try { runtime.messages().send(id, request, sender); return "saved"; }
        catch (StateConflictException expected) { return "conflict"; }
    }

    @SafeVarargs
    private static <T> List<T> together(java.util.concurrent.Callable<T>... actions) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(actions.length);
        CountDownLatch ready = new CountDownLatch(actions.length);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (var action : actions) { futures.add(workers.submit(() -> { ready.countDown(); await(start); return action.call(); })); }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<T> results = new ArrayList<>();
            for (var future : futures) { results.add(future.get(12, TimeUnit.SECONDS)); }
            return results;
        } finally {
            start.countDown(); workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    private CurrentUser user() {
        String account = UUID.randomUUID() + "@example.invalid";
        jdbc.update("INSERT INTO users(public_uid,email,password_hash,nickname,school_id,role,status,created_at,updated_at) VALUES(?,?,?,?,?,'USER','active',NOW(),NOW())",
                Integer.toString(PUBLIC_UID.getAndIncrement()), account, UUID.randomUUID().toString(), UUID.randomUUID().toString(), schoolId);
        long id = jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, account);
        ownedUsers.add(id);
        return new CurrentUser(id, account, "USER");
    }

    private static String uid(CurrentUser user) { return jdbc.queryForObject("SELECT public_uid FROM users WHERE id=?", String.class, user.id()); }

    private static long conversation(CurrentUser first, CurrentUser second, LocalDateTime time) {
        long low = Math.min(first.id(), second.id());
        long high = Math.max(first.id(), second.id());
        jdbc.update("INSERT INTO conversations(user_one_id,user_two_id,last_message_at,created_at,updated_at) VALUES(?,?,?,?,?)", low, high, time, time, time);
        return jdbc.queryForObject("SELECT id FROM conversations WHERE user_one_id=? AND user_two_id=?", Long.class, low, high);
    }

    private static long seed(long conversation, CurrentUser sender, CurrentUser receiver, String content) {
        jdbc.update("INSERT INTO messages(conversation_id,sender_id,receiver_id,content,is_read,created_at) VALUES(?,?,?,?,false,?)",
                conversation, sender.id(), receiver.id(), content, AppTime.nowLocal().truncatedTo(ChronoUnit.SECONDS));
        return jdbc.queryForObject("SELECT MAX(id) FROM messages WHERE conversation_id=?", Long.class, conversation);
    }

    private static int unread(long conversation, CurrentUser receiver) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM messages WHERE conversation_id=? AND receiver_id=? AND is_read=false", Integer.class, conversation, receiver.id());
    }

    private static int count(String sql, long id) { return jdbc.queryForObject(sql, Integer.class, id); }
}
