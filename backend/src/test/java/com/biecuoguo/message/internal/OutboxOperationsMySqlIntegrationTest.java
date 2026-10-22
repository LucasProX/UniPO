package com.biecuoguo.message.internal;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.biecuoguo.admin.api.AdminAuditApi;
import com.biecuoguo.admin.internal.AdminAuditLog;
import com.biecuoguo.admin.internal.AdminAuditLogMapper;
import com.biecuoguo.admin.internal.AdminAuditService;
import com.biecuoguo.error.StateConflictException;
import com.biecuoguo.message.api.OutboxOperationsDtos;
import com.biecuoguo.security.CurrentUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real administrative permissions, database changes and required audit share the same owned MySQL. */
@EnabledIfEnvironmentVariable(named = "UNIPO_DP14_OPS_MYSQL_INTEGRATION", matches = "1")
@Timeout(30)
class OutboxOperationsMySqlIntegrationTest {
    private static final AtomicInteger PUBLIC_UID = new AtomicInteger(79_000_000);
    private static final AtomicBoolean FAIL_AFTER_AUDIT_INSERT = new AtomicBoolean();
    private static final AtomicInteger OPS_QUERIES = new AtomicInteger();
    private static AnnotationConfigApplicationContext context;
    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;
    private static OutboxOperationsService operations;
    private static AdminAuditApi audit;
    private static long schoolId;
    private final List<Long> ownedUsers = new ArrayList<>();

    @BeforeAll
    static void migrateOnlyAnEmptyOwnedFixture() {
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
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=2000&socketTimeout=10000");
        config.setUsername("unipo_fixture");
        config.setPassword(password);
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(2_000);
        config.setValidationTimeout(500);
        config.setPoolName("outbox-ops-" + run);
        pool = new HikariDataSource(config);
        jdbc = new JdbcTemplate(pool);
        assertEquals(schema, jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertEquals(run, jdbc.queryForObject("SELECT LOWER(HEX(run_id)) FROM dp02_fixture_marker", String.class));
        assertEquals(List.of("dp02_fixture_marker"), jdbc.queryForList("SHOW TABLES", String.class));
        Flyway flyway = Flyway.configure().dataSource(pool).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("0").createSchemas(false).cleanDisabled(true).load();
        flyway.migrate();
        assertEquals(0, flyway.info().pending().length);
        assertTrue(flyway.validateWithResult().validationSuccessful);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '24' AND success = 1",
                Integer.class));
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> pool);
        context.register(FixtureConfiguration.class);
        context.refresh();
        operations = context.getBean(OutboxOperationsService.class);
        audit = context.getBean(AdminAuditApi.class);
        assertTrue(AopUtils.isAopProxy(operations));
        assertTrue(AopUtils.isAopProxy(audit));
        String code = "ops-" + UUID.randomUUID();
        jdbc.update("INSERT INTO schools (name, code, created_at, updated_at) VALUES (?, ?, NOW(), NOW())", "Ops fixture", code);
        schoolId = jdbc.queryForObject("SELECT id FROM schools WHERE code = ?", Long.class, code);
        System.out.println("Owned outbox operations fixture: complete Flyway chain and actual method-security/transaction proxies active");
    }

    @AfterEach
    void removeOnlyOwnedRows() {
        SecurityContextHolder.clearContext();
        FAIL_AFTER_AUDIT_INSERT.set(false);
        for (long id : ownedUsers) {
            jdbc.update("DELETE FROM admin_audit_logs WHERE actor_id = ?", id);
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }

    @AfterAll
    static void closeFixture() {
        try {
            if (schoolId != 0) { jdbc.update("DELETE FROM schools WHERE id = ?", schoolId); }
        } finally {
            if (context != null) { context.close(); }
            if (pool != null) { pool.close(); }
        }
    }

    @Test
    void requiredAuditAndRetryCommitTogetherWithoutReplacingEventOrDeviceProgress() throws Exception {
        CurrentUser admin = actor("ADMIN");
        long id = seedDelivery("FAILED", 4);
        String event = jdbc.queryForObject("SELECT event_id FROM message_delivery_outbox WHERE id = ?", String.class, id);
        long receiver = ownedUsers.getLast();
        jdbc.update("INSERT INTO push_delivery_batches (event_id, user_id, snapshot_max_token_id, next_after_token_id, created_at) "
                + "VALUES (?, ?, 77, 77, UTC_TIMESTAMP(6))", event, receiver);
        jdbc.update("INSERT INTO push_delivery_receipts (event_id, device_token_id, outcome, created_at) "
                + "VALUES (?, 77, 'ACCEPTED', UTC_TIMESTAMP(6))", event);
        authenticate(admin);

        var result = operations.retry(id, new OutboxOperationsDtos.RetryRequest(4, "配置修复后重试"), admin);

        assertEquals("PENDING", result.state());
        assertEquals(4, result.attempts());
        assertEquals(event, result.eventId());
        assertEquals("APNS_REJECTED", result.lastError());
        assertEquals(1, auditCount(id));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM push_delivery_receipts WHERE event_id = ? AND outcome = 'ACCEPTED'",
                Integer.class, event));
        assertTrue(jdbc.queryForObject("SELECT available_at <= UTC_TIMESTAMP(6) AND completed_at IS NULL "
                + "AND claim_token IS NULL AND lease_until IS NULL FROM message_delivery_outbox WHERE id = ?", Boolean.class, id));
        String detail = jdbc.queryForObject("SELECT detail FROM admin_audit_logs WHERE target_id = ?", String.class, String.valueOf(id));
        var json = new ObjectMapper().readTree(detail);
        assertEquals("FAILED", json.path("previousState").asText());
        assertEquals("PENDING", json.path("nextState").asText());
        assertEquals("配置修复后重试", json.path("reason").asText());
    }

    @Test
    void aFailureAfterTheRealAuditInsertRollsBackBothTheRetryAndAudit() {
        CurrentUser admin = actor("ADMIN");
        long id = seedDelivery("FAILED", 4);
        authenticate(admin);
        FAIL_AFTER_AUDIT_INSERT.set(true);

        assertThrows(IllegalStateException.class, () -> operations.retry(id,
                new OutboxOperationsDtos.RetryRequest(4, "隔离审计回滚检查"), admin));

        assertEquals("FAILED", state(id));
        assertEquals(4, attempts(id));
        assertEquals(0, auditCount(id), "The actual INSERT before the failure must roll back with the delivery update");
    }

    @Test
    void concurrentRetriesWithTheSameObservedAttemptsCreateOnlyOneAudit() throws Exception {
        CurrentUser admin = actor("ADMIN");
        long id = seedDelivery("FAILED", 4);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                results.add(workers.submit(() -> {
                    authenticate(admin);
                    try {
                        ready.countDown();
                        await(start);
                        operations.retry(id, new OutboxOperationsDtos.RetryRequest(4, "同版本并发重试"), admin);
                        return true;
                    } catch (StateConflictException expectedConflict) {
                        return false;
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                }));
            }
            await(ready);
            start.countDown();
            int successes = 0;
            for (Future<Boolean> result : results) { if (result.get(8, TimeUnit.SECONDS)) { successes++; } }
            assertEquals(1, successes);
            assertEquals("PENDING", state(id));
            assertEquals(1, auditCount(id));
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void otherStatesAndStaleAttemptsCannotBeRetriedOrAudited() {
        CurrentUser admin = actor("ADMIN");
        authenticate(admin);
        for (String state : List.of("PENDING", "SENDING", "DELIVERED", "SUPPRESSED")) {
            long id = seedDelivery(state, 4);
            assertThrows(StateConflictException.class, () -> operations.retry(id,
                    new OutboxOperationsDtos.RetryRequest(4, "不允许的状态"), admin));
            assertEquals(state, state(id));
            assertEquals(0, auditCount(id));
        }
        long failed = seedDelivery("FAILED", 5);
        assertThrows(StateConflictException.class, () -> operations.retry(failed,
                new OutboxOperationsDtos.RetryRequest(4, "旧尝试次数"), admin));
        assertEquals("FAILED", state(failed));
        assertEquals(0, auditCount(failed));
    }

    @Test
    void nonAdminsAndMissingAuthenticationNeverReachTheDeliveryMapper() {
        for (String role : List.of("USER", "OPERATOR", "SCHOOL_OPERATOR", "COLLEGE_OPERATOR", "MAJOR_OPERATOR")) {
            CurrentUser viewer = actor(role);
            authenticate(viewer);
            int before = OPS_QUERIES.get();
            assertThrows(AccessDeniedException.class, () -> operations.list(null, null, null, 20, viewer));
            assertThrows(AccessDeniedException.class, () -> operations.retry(1,
                    new OutboxOperationsDtos.RetryRequest(0, "不得调用"), viewer));
            assertEquals(before, OPS_QUERIES.get());
        }
        SecurityContextHolder.clearContext();
        int before = OPS_QUERIES.get();
        assertThrows(AuthenticationCredentialsNotFoundException.class,
                () -> operations.list(null, null, null, 20, null));
        assertEquals(before, OPS_QUERIES.get());
    }

    @Test
    void paginationIsExclusiveAndBoundToTheStateAndChannel() {
        CurrentUser admin = actor("ADMIN");
        authenticate(admin);
        long first = seedDelivery("FAILED", 1);
        long second = seedDelivery("FAILED", 2);
        long third = seedDelivery("FAILED", 3);
        var page = operations.list("FAILED", "PUSH", null, 2, admin);
        assertEquals(List.of(third, second), page.items().stream().map(OutboxOperationsDtos.DeliveryView::id).toList());
        assertTrue(page.hasMore());
        assertNotNull(page.nextCursor());
        var last = operations.list("FAILED", "PUSH", page.nextCursor(), 2, admin);
        assertEquals(List.of(first), last.items().stream().map(OutboxOperationsDtos.DeliveryView::id).toList());
        assertFalse(last.hasMore());
        assertEquals(null, last.nextCursor());
        int before = OPS_QUERIES.get();
        assertThrows(IllegalArgumentException.class, () -> operations.list("PENDING", "PUSH", page.nextCursor(), 2, admin));
        assertThrows(IllegalArgumentException.class, () -> operations.list("FAILED", "REALTIME", page.nextCursor(), 2, admin));
        assertEquals(before, OPS_QUERIES.get());
    }

    @Test
    void malformedFiltersAndCursorsAreRejectedBeforeQuerying() {
        CurrentUser admin = actor("ADMIN");
        authenticate(admin);
        int before = OPS_QUERIES.get();
        assertThrows(IllegalArgumentException.class, () -> operations.list("UNKNOWN", null, null, 20, admin));
        assertThrows(IllegalArgumentException.class, () -> operations.list(null, "EMAIL", null, 20, admin));
        assertThrows(IllegalArgumentException.class, () -> operations.list(null, null, null, 0, admin));
        assertThrows(IllegalArgumentException.class, () -> operations.list(null, null, "not-a-valid-cursor", 20, admin));
        assertEquals(before, OPS_QUERIES.get());
        assertEquals(50, operations.list(null, null, null, 500, admin).limit());
    }

    @Test
    void metadataHasOnlyAllowedFieldsAndExplicitUtcIndependentOfJvmZone() throws Exception {
        CurrentUser admin = actor("ADMIN");
        long id = seedDelivery("FAILED", 4);
        jdbc.update("UPDATE message_delivery_outbox SET created_at = '2026-05-12 01:02:03.456789', "
                + "available_at = '2026-05-12 01:02:04.456789' WHERE id = ?", id);
        authenticate(admin);
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
            var metadata = operations.list(null, null, null, 20, admin).items().getFirst();
            assertEquals(ZoneOffset.UTC, metadata.createdAt().getOffset());
            assertEquals("2026-05-12T01:02:03.456789Z", metadata.createdAt().toString());
            ObjectMapper json = new ObjectMapper().findAndRegisterModules();
            var tree = json.valueToTree(metadata);
            Set<String> names = new HashSet<>();
            tree.fieldNames().forEachRemaining(names::add);
            assertEquals(Set.of("id", "eventId", "messageId", "channel", "state", "attempts", "lastError",
                    "createdAt", "availableAt", "leaseUntil", "completedAt"), names);
            assertFalse(tree.toString().contains("private fixture body"));
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    @Test
    void missingRowsAndInvalidReasonsDoNotCreateAuditOrChangeRecords() {
        CurrentUser admin = actor("ADMIN");
        authenticate(admin);
        assertThrows(java.util.NoSuchElementException.class, () -> operations.retry(Long.MAX_VALUE,
                new OutboxOperationsDtos.RetryRequest(0, "记录已不存在"), admin));
        long id = seedDelivery("FAILED", 4);
        for (String reason : List.of("", "\n", "a\nb", "x".repeat(201))) {
            assertThrows(IllegalArgumentException.class, () -> operations.retry(id,
                    new OutboxOperationsDtos.RetryRequest(4, reason), admin));
        }
        assertEquals("FAILED", state(id));
        assertEquals(0, auditCount(id));
    }

    @Test
    void requiredAuditCannotBeCalledWithoutTheBusinessTransaction() {
        CurrentUser admin = actor("ADMIN");
        authenticate(admin);
        assertThrows(IllegalTransactionStateException.class,
                () -> audit.recordRequired(admin, "MESSAGE_DELIVERY_RETRY", "message_delivery", 1, "not committed"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs WHERE actor_id = ?", Integer.class, admin.id()));
    }

    private CurrentUser actor(String role) {
        String account = "ops-" + UUID.randomUUID();
        String nickname = "ops-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users (public_uid, email, password_hash, nickname, school_id, role, status, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, 'active', NOW(), NOW())", Integer.toString(PUBLIC_UID.getAndIncrement()),
                account, UUID.randomUUID().toString(), nickname, schoolId, role);
        Long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, account);
        assertNotNull(id);
        ownedUsers.add(id);
        return new CurrentUser(id, account, role);
    }

    private long seedDelivery(String state, int attempts) {
        CurrentUser sender = actor("USER");
        CurrentUser receiver = actor("USER");
        long low = Math.min(sender.id(), receiver.id());
        long high = Math.max(sender.id(), receiver.id());
        jdbc.update("INSERT INTO conversations (user_one_id, user_two_id, last_message_at, created_at, updated_at) "
                + "VALUES (?, ?, NOW(), NOW(), NOW())", low, high);
        Long conversation = jdbc.queryForObject("SELECT id FROM conversations WHERE user_one_id = ? AND user_two_id = ?", Long.class, low, high);
        String body = "private fixture body " + UUID.randomUUID();
        jdbc.update("INSERT INTO messages (conversation_id, sender_id, receiver_id, content, is_read, created_at) "
                + "VALUES (?, ?, ?, ?, false, NOW())", conversation, sender.id(), receiver.id(), body);
        Long message = jdbc.queryForObject("SELECT id FROM messages WHERE conversation_id = ?", Long.class, conversation);
        String event = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO message_delivery_outbox (event_id, message_id, channel, state, attempts, last_error, "
                + "created_at, available_at, completed_at, claim_token, lease_until) "
                + "VALUES (?, ?, 'PUSH', ?, ?, 'APNS_REJECTED', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), "
                + "CASE WHEN ? = 'SENDING' THEN ? ELSE NULL END, "
                + "CASE WHEN ? = 'SENDING' THEN TIMESTAMPADD(SECOND, 60, UTC_TIMESTAMP(6)) ELSE NULL END)",
                event, message, state, attempts, state, UUID.randomUUID().toString().replace("-", ""), state);
        Long id = jdbc.queryForObject("SELECT id FROM message_delivery_outbox WHERE event_id = ?", Long.class, event);
        assertNotNull(id);
        return id;
    }

    private static void authenticate(CurrentUser actor) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + actor.role()))));
    }

    private static String state(long id) {
        return jdbc.queryForObject("SELECT state FROM message_delivery_outbox WHERE id = ?", String.class, id);
    }

    private static int attempts(long id) {
        return jdbc.queryForObject("SELECT attempts FROM message_delivery_outbox WHERE id = ?", Integer.class, id);
    }

    private static int auditCount(long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs WHERE action = 'MESSAGE_DELIVERY_RETRY' "
                + "AND target_id = ?", Integer.class, String.valueOf(id));
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS), "The owned retry gate did not progress"); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Owned retry gate interrupted", interrupted);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @EnableMethodSecurity
    static class FixtureConfiguration {
        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            MybatisConfiguration config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true);
            config.setDefaultStatementTimeout(5);
            config.addMapper(OutboxOperationsMapper.class);
            config.addMapper(AdminAuditLogMapper.class);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setConfiguration(config);
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) { return new SqlSessionTemplate(factory); }

        @Bean
        DataSourceTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }

        @Bean
        OutboxOperationsMapper operationsMapper(SqlSessionTemplate template) {
            OutboxOperationsMapper real = template.getMapper(OutboxOperationsMapper.class);
            return (OutboxOperationsMapper) Proxy.newProxyInstance(OutboxOperationsMapper.class.getClassLoader(),
                    new Class<?>[]{OutboxOperationsMapper.class}, (proxy, method, args) -> {
                        if (method.getDeclaringClass() == OutboxOperationsMapper.class) { OPS_QUERIES.incrementAndGet(); }
                        try { return method.invoke(real, args); }
                        catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
        }

        @Bean
        AdminAuditService auditService(SqlSessionTemplate template) {
            AdminAuditLogMapper real = template.getMapper(AdminAuditLogMapper.class);
            AdminAuditLogMapper gate = (AdminAuditLogMapper) Proxy.newProxyInstance(AdminAuditLogMapper.class.getClassLoader(),
                    new Class<?>[]{AdminAuditLogMapper.class}, (proxy, method, args) -> {
                        try {
                            Object result = method.invoke(real, args);
                            if (method.getName().equals("insert") && args != null && args.length == 1
                                    && args[0] instanceof AdminAuditLog && FAIL_AFTER_AUDIT_INSERT.getAndSet(false)) {
                                throw new IllegalStateException("Synthetic failure after the owned real audit INSERT");
                            }
                            return result;
                        } catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
            return new AdminAuditService(gate);
        }

        @Bean
        OutboxOperationsService operationsService(OutboxOperationsMapper mapper, AdminAuditApi audit) {
            return new OutboxOperationsService(mapper, audit, new ObjectMapper());
        }
    }
}
