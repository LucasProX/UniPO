package com.biecuoguo.push.internal;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.biecuoguo.mapper.DeviceTokenMapper;
import com.biecuoguo.push.api.PushDeliveryException;
import com.eatthepath.pushy.apns.ApnsClient;
import com.eatthepath.pushy.apns.PushNotificationResponse;
import com.eatthepath.pushy.apns.util.SimpleApnsPushNotification;
import com.eatthepath.pushy.apns.util.concurrent.PushNotificationFuture;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Real device ownership, high-water snapshots and delivery receipts; no Apple network calls. */
@EnabledIfEnvironmentVariable(named = "UNIPO_DP14_PUSH_MYSQL_INTEGRATION", matches = "1")
@Timeout(30)
class PushDeliveryMySqlIntegrationTest {
    private static final AtomicInteger PUBLIC_UID = new AtomicInteger(77_000_000);
    private static DriverManagerDataSource source;
    private static JdbcTemplate jdbc;
    private static RuntimeContext runtime;
    private static long schoolId;
    private final List<Long> ownedUsers = new ArrayList<>();

    @BeforeAll
    static void migrateOnlyThisNewOwnedDatabase() {
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
        source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl("jdbc:mysql://127.0.0.1:22306/" + schema
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=2000&socketTimeout=10000");
        source.setUsername("unipo_fixture");
        source.setPassword(password);
        jdbc = new JdbcTemplate(source);
        assertEquals(schema, jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertEquals(run, jdbc.queryForObject("SELECT LOWER(HEX(run_id)) FROM dp02_fixture_marker", String.class));
        assertEquals(List.of("dp02_fixture_marker"), jdbc.queryForList("SHOW TABLES", String.class));
        Flyway flyway = Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("0").createSchemas(false).cleanDisabled(true).load();
        flyway.migrate();
        assertEquals(0, flyway.info().pending().length);
        assertTrue(flyway.validateWithResult().validationSuccessful);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '24' AND success = 1",
                Integer.class));
        runtime = new RuntimeContext();
        assertTrue(AopUtils.isAopProxy(runtime.progress()));
        assertTrue(AopUtils.isAopProxy(runtime.tokens()));
        String code = "push-fixture-" + UUID.randomUUID();
        jdbc.update("INSERT INTO schools (name, code, created_at, updated_at) VALUES (?, ?, NOW(), NOW())",
                "Push ledger fixture", code);
        Long createdSchool = jdbc.queryForObject("SELECT id FROM schools WHERE code = ?", Long.class, code);
        assertNotNull(createdSchool);
        schoolId = createdSchool;
        System.out.println("DP14 push ledger owned MySQL V1-V24, real token registration and persistent progress verified");
    }

    @AfterEach
    void removeOnlyRowsOwnedByThisTest() {
        for (long user : ownedUsers) { jdbc.update("DELETE FROM users WHERE id = ?", user); }
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
    void pagesAreBoundedAndReceiptsLetEveryOriginalDeviceFinishExactlyOnce() {
        long user = newUser();
        List<Long> ids = register(user, 137);
        var batch = runtime.progress().open(event(), user);
        Set<Long> observed = new HashSet<>();
        for (int pass = 0; pass < 3; pass++) {
            var page = runtime.progress().pending(batch, 64);
            assertEquals(pass == 2 ? 9 : 64, page.size());
            for (var target : page) {
                assertTrue(observed.add(target.id()), "An acknowledged target must not return in a later page");
                runtime.progress().accepted(batch, target.id());
            }
        }
        assertEquals(new HashSet<>(ids), observed);
        assertFalse(runtime.progress().hasPending(batch));
        assertTrue(runtime.progress().pending(batch, 64).isEmpty());
        assertEquals(137, receiptCount(batch.eventId(), "ACCEPTED"));
        assertThrows(IllegalArgumentException.class, () -> runtime.progress().pending(batch, 0));
        assertThrows(IllegalArgumentException.class, () -> runtime.progress().pending(batch, 65));
    }

    @Test
    void apnsPartialSuccessSurvivesContextRebuildAndOnlyTheFailedDeviceIsRetried() {
        long user = newUser();
        register(user, 2);
        List<String> registered = tokens(user);
        String successful = registered.getFirst();
        String retrying = registered.get(1);
        String event = event();
        ApnsClient client = mock(ApnsClient.class);
        ConcurrentLinkedQueue<String> submitted = new ConcurrentLinkedQueue<>();
        AtomicInteger failures = new AtomicInteger(1);
        when(client.sendNotification(any(SimpleApnsPushNotification.class))).thenAnswer(call -> {
            SimpleApnsPushNotification notification = call.getArgument(0);
            submitted.add(notification.getToken());
            boolean accepted = !notification.getToken().equals(retrying) || failures.getAndDecrement() <= 0;
            return response(notification, accepted, accepted ? 200 : 503, accepted ? null : "ServiceUnavailable");
        });
        var failure = assertThrows(PushDeliveryException.class, () -> service(runtime, client)
                .send(user, "fixture title", "fixture body", Map.of("eventId", event)));
        assertEquals("APNS_SERVER_ERROR", failure.code());
        assertTrue(failure.retryable());
        assertEquals(1, receiptCount(event, "ACCEPTED"));

        try (RuntimeContext rebuilt = new RuntimeContext()) {
            service(rebuilt, client).send(user, "fixture title", "fixture body", Map.of("eventId", event));
            assertFalse(rebuilt.progress().hasPending(rebuilt.progress().open(event, user)));
        }
        assertEquals(1, submitted.stream().filter(successful::equals).count());
        assertEquals(2, submitted.stream().filter(retrying::equals).count());
        assertEquals(2, receiptCount(event, "ACCEPTED"));
    }

    @Test
    void apnsOnlySubmitsOneBoundedPageAndSignalsMoreTargetsUntilTheSnapshotFinishes() {
        long user = newUser();
        register(user, 131);
        ApnsClient client = mock(ApnsClient.class);
        List<String> submitted = new ArrayList<>();
        when(client.sendNotification(any(SimpleApnsPushNotification.class))).thenAnswer(call -> {
            SimpleApnsPushNotification notification = call.getArgument(0);
            submitted.add(notification.getToken());
            return response(notification, true, 200, null);
        });
        var service = service(runtime, client);
        Map<String, String> data = Map.of("eventId", event());
        for (int pass = 0; pass < 2; pass++) {
            int before = submitted.size();
            PushDeliveryException more = assertThrows(PushDeliveryException.class,
                    () -> service.send(user, "fixture", "fixture", data));
            assertEquals("APNS_MORE_TARGETS", more.code());
            assertTrue(more.retryable());
            assertEquals(64, submitted.size() - before);
        }
        service.send(user, "fixture", "fixture", data);
        assertEquals(131, submitted.size());
        assertEquals(131, new HashSet<>(submitted).size());
    }

    @Test
    void invalidDevicesLeaveDurableReceiptsWithoutDeletingLaterRegistrations() {
        long owner = newUser();
        long newOwner = newUser();
        register(owner, 2);
        List<String> registered = tokens(owner);
        String invalid = registered.getFirst();
        String event = event();
        ApnsClient client = mock(ApnsClient.class);
        List<String> submitted = new ArrayList<>();
        when(client.sendNotification(any(SimpleApnsPushNotification.class))).thenAnswer(call -> {
            SimpleApnsPushNotification notification = call.getArgument(0);
            submitted.add(notification.getToken());
            boolean gone = invalid.equals(notification.getToken());
            if (gone) { runtime.tokens().register(newOwner, invalid, "ios", "sandbox"); }
            return response(notification, !gone, gone ? 410 : 200, gone ? "Unregistered" : null);
        });
        service(runtime, client).send(owner, "fixture", "fixture", Map.of("eventId", event));
        assertEquals(1, receiptCount(event, "INVALID"));
        assertEquals(1, receiptCount(event, "ACCEPTED"));
        assertEquals(newOwner, jdbc.queryForObject("SELECT user_id FROM device_tokens WHERE token = ?", Long.class, invalid));
        try (RuntimeContext rebuilt = new RuntimeContext()) {
            service(rebuilt, client).send(owner, "fixture", "fixture", Map.of("eventId", event));
        }
        assertEquals(2, submitted.size(), "Neither a completed device nor a transferred registration may be retried");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM push_delivery_receipts WHERE event_id = ?", Integer.class, event));
    }

    @Test
    void laterRegistrationsDoNotEnterAnOldSnapshotEvenWhenTheCallerForgesTheHighWaterMark() {
        long user = newUser();
        long first = register(user, 1).getFirst();
        var original = runtime.progress().open(event(), user);
        register(user, 1);
        var reopened = runtime.progress().open(original.eventId(), user);
        assertEquals(original.maxTokenId(), reopened.maxTokenId());
        var forged = new PushDeliveryProgress.Batch(original.eventId(), user, Long.MAX_VALUE);
        var targets = runtime.progress().pending(forged, 64);
        assertEquals(List.of(first), targets.stream().map(PushDeliveryProgress.Target::id).toList());
        runtime.progress().accepted(original, first);
        assertFalse(runtime.progress().hasPending(forged));
        var newer = runtime.progress().open(event(), user);
        assertEquals(2, runtime.progress().pending(newer, 64).size());
    }

    @Test
    void tokenTransferAndReregistrationCannotResurrectTheOriginalDeviceTarget() {
        long originalOwner = newUser();
        long newOwner = newUser();
        long oldId = register(originalOwner, 1).getFirst();
        String token = tokens(originalOwner).getFirst();
        var batch = runtime.progress().open(event(), originalOwner);
        var selected = runtime.progress().pending(batch, 64).getFirst();
        assertTrue(runtime.progress().isCurrentTarget(batch, selected));
        runtime.tokens().register(newOwner, token, "ios", "sandbox");
        assertFalse(runtime.progress().isCurrentTarget(batch, selected));
        assertTrue(runtime.progress().pending(batch, 64).isEmpty());
        assertFalse(runtime.progress().hasPending(batch));
        long movedId = jdbc.queryForObject("SELECT id FROM device_tokens WHERE token = ?", Long.class, token);
        assertNotEquals(oldId, movedId);
        runtime.tokens().register(originalOwner, token, "ios", "sandbox");
        assertTrue(runtime.progress().pending(batch, 64).isEmpty());
        var newBatch = runtime.progress().open(event(), originalOwner);
        assertEquals(1, runtime.progress().pending(newBatch, 64).size());
        runtime.progress().invalid(batch, oldId);
        assertEquals(1, receiptCount(batch.eventId(), "INVALID"));
        assertEquals(originalOwner, jdbc.queryForObject("SELECT user_id FROM device_tokens WHERE token = ?", Long.class, token));
    }

    @Test
    void apnsRechecksOwnershipAfterThePageWasReadAndBeforeSubmittingTheDevice() {
        long originalOwner = newUser();
        long newOwner = newUser();
        register(originalOwner, 1);
        String token = tokens(originalOwner).getFirst();
        PushDeliveryProgress real = runtime.progress();
        PushDeliveryProgress afterPage = mock(PushDeliveryProgress.class, delegatesTo(real));
        doAnswer(call -> {
            var page = real.pending(call.getArgument(0), call.getArgument(1));
            runtime.tokens().register(newOwner, token, "ios", "sandbox");
            return page;
        }).when(afterPage).pending(any(), eq(64));
        ApnsClient client = mock(ApnsClient.class);
        ApnsPushService service = new ApnsPushService(afterPage, new ObjectMapper(), client, Duration.ofSeconds(10), () -> 0L);

        service.send(originalOwner, "fixture", "fixture", Map.of("eventId", event()));

        verify(client, never()).sendNotification(any(SimpleApnsPushNotification.class));
        assertEquals(newOwner, jdbc.queryForObject("SELECT user_id FROM device_tokens WHERE token = ?", Long.class, token));
    }

    @Test
    void aReusedEventCannotBeOpenedReadOrAcknowledgedUnderAnotherAccount() {
        long owner = newUser();
        long intruder = newUser();
        long tokenId = register(owner, 1).getFirst();
        var batch = runtime.progress().open(event(), owner);
        var forged = new PushDeliveryProgress.Batch(batch.eventId(), intruder, batch.maxTokenId());
        assertThrows(IllegalArgumentException.class, () -> runtime.progress().open(batch.eventId(), intruder));
        assertThrows(IllegalArgumentException.class, () -> runtime.progress().pending(forged, 64));
        assertThrows(IllegalArgumentException.class, () -> runtime.progress().hasPending(forged));
        assertThrows(IllegalArgumentException.class, () -> runtime.progress().accepted(forged, tokenId));
        assertThrows(IllegalArgumentException.class, () -> runtime.progress().invalid(forged, tokenId));
        assertEquals(0, receiptCount(batch.eventId(), "ACCEPTED"));
        assertTrue(runtime.progress().hasPending(batch));
    }

    @Test
    void anEmptySnapshotStaysCompleteWhenTheUserRegistersItsFirstDeviceLater() {
        long user = newUser();
        var empty = runtime.progress().open(event(), user);
        assertEquals(0, empty.maxTokenId());
        register(user, 1);
        assertFalse(runtime.progress().hasPending(empty));
        assertTrue(runtime.progress().pending(empty, 64).isEmpty());
        assertEquals(0, runtime.progress().open(empty.eventId(), user).maxTokenId());
        assertTrue(runtime.progress().hasPending(runtime.progress().open(event(), user)));
    }

    @Test
    void aPersistedCursorLetsTailDevicesProgressDespiteAnUnacknowledgedFirstPage() {
        long user = newUser();
        List<Long> all = register(user, 80);
        var batch = runtime.progress().open(event(), user);
        var first = runtime.progress().pending(batch, 64);
        assertEquals(64, first.size());
        List<PushDeliveryProgress.Target> tail;
        try (RuntimeContext rebuilt = new RuntimeContext()) { tail = rebuilt.progress().pending(batch, 64); }
        assertEquals(16, tail.size(), "A restart must resume the cursor rather than repeat a failing first page forever");
        Set<Long> observed = new HashSet<>(first.stream().map(PushDeliveryProgress.Target::id).toList());
        observed.addAll(tail.stream().map(PushDeliveryProgress.Target::id).toList());
        assertEquals(new HashSet<>(all), observed);
        for (var device : tail) { runtime.progress().accepted(batch, device.id()); }
        assertEquals(new HashSet<>(first.stream().map(PushDeliveryProgress.Target::id).toList()),
                new HashSet<>(runtime.progress().pending(batch, 64).stream().map(PushDeliveryProgress.Target::id).toList()));
    }

    @Test
    void concurrentOpeningUsesOnePersistentSnapshotAndOneEventOwner() {
        long user = newUser();
        register(user, 3);
        String event = event();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            List<Future<PushDeliveryProgress.Batch>> tasks = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                tasks.add(workers.submit(() -> {
                    ready.countDown();
                    await(start);
                    return runtime.progress().open(event, user);
                }));
            }
            await(ready);
            start.countDown();
            assertEquals(result(tasks.getFirst()), result(tasks.get(1)));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM push_delivery_batches WHERE event_id = ?", Integer.class, event));
        } finally {
            start.countDown();
            workers.shutdownNow();
            try { assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS)); }
            catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Push fixture worker shutdown interrupted", failure);
            }
        }
    }

    private static ApnsPushService service(RuntimeContext context, ApnsClient client) {
        return new ApnsPushService(context.progress(), new ObjectMapper(), client,
                Duration.ofSeconds(10), () -> 0L);
    }

    private static PushNotificationFuture<SimpleApnsPushNotification,
            PushNotificationResponse<SimpleApnsPushNotification>> response(SimpleApnsPushNotification notification,
                                                                          boolean accepted, int status, String reason) {
        @SuppressWarnings("unchecked")
        var response = (PushNotificationResponse<SimpleApnsPushNotification>) mock(PushNotificationResponse.class);
        when(response.isAccepted()).thenReturn(accepted);
        when(response.getStatusCode()).thenReturn(status);
        when(response.getRejectionReason()).thenReturn(Optional.ofNullable(reason));
        var future = new PushNotificationFuture<SimpleApnsPushNotification, PushNotificationResponse<SimpleApnsPushNotification>>(notification);
        future.complete(response);
        return future;
    }

    private long newUser() {
        String account = "push-" + UUID.randomUUID() + "@example.invalid";
        jdbc.update("INSERT INTO users (public_uid, email, password_hash, nickname, school_id, role, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'USER', 'active', NOW(), NOW())",
                Integer.toString(PUBLIC_UID.getAndIncrement()), account, UUID.randomUUID().toString(), "push-" + UUID.randomUUID(), schoolId);
        Long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, account);
        assertNotNull(id);
        ownedUsers.add(id);
        return id;
    }

    private List<Long> register(long user, int count) {
        List<Long> ids = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
            runtime.tokens().register(user, token, "ios", "sandbox");
            ids.add(jdbc.queryForObject("SELECT id FROM device_tokens WHERE token = ?", Long.class, token));
        }
        return ids;
    }

    private List<String> tokens(long user) {
        return jdbc.queryForList("SELECT token FROM device_tokens WHERE user_id = ? ORDER BY id", String.class, user);
    }

    private int receiptCount(String event, String outcome) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM push_delivery_receipts WHERE event_id = ? AND outcome = ?", Integer.class, event, outcome);
    }

    private String event() { return UUID.randomUUID().toString().replace("-", ""); }

    private static void await(CountDownLatch gate) {
        try { assertTrue(gate.await(5, TimeUnit.SECONDS), "Push fixture gate did not progress"); }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Push fixture gate interrupted", failure);
        }
    }

    private static <T> T result(Future<T> task) {
        try { return task.get(10, TimeUnit.SECONDS); }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Push fixture result interrupted", failure);
        } catch (ExecutionException failure) { throw new IllegalStateException("Push fixture action failed", failure.getCause()); }
        catch (java.util.concurrent.TimeoutException failure) { throw new IllegalStateException("Push fixture action timed out", failure); }
    }

    static final class RuntimeContext implements AutoCloseable {
        private final AnnotationConfigApplicationContext application;

        RuntimeContext() {
            application = new AnnotationConfigApplicationContext();
            application.registerBean(DataSource.class, () -> source);
            application.register(FixtureConfiguration.class);
            application.refresh();
        }

        PushDeliveryProgress progress() { return application.getBean(PushDeliveryProgress.class); }
        PushTokenService tokens() { return application.getBean(PushTokenService.class); }
        @Override public void close() { application.close(); }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class FixtureConfiguration {
        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setDefaultStatementTimeout(5);
            configuration.addMapper(DeviceTokenMapper.class);
            configuration.addMapper(PushDeliveryProgressMapper.class);
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
        PushDeliveryProgress progress(SqlSessionTemplate template) { return new PushDeliveryProgress(template.getMapper(PushDeliveryProgressMapper.class)); }

        @Bean
        PushTokenService tokens(SqlSessionTemplate template) { return new PushTokenService(template.getMapper(DeviceTokenMapper.class), null); }
    }
}
