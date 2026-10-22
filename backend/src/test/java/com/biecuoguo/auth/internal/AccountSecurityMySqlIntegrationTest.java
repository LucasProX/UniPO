package com.biecuoguo.auth.internal;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.biecuoguo.auth.api.AccountSecurityApi;
import com.biecuoguo.config.AppProperties;
import com.biecuoguo.domain.User;
import com.biecuoguo.mapper.UserMapper;
import com.biecuoguo.security.CurrentUser;
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
import org.springframework.security.core.AuthenticationException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real MySQL transactions and all repository Flyway migrations, without a Boot application context.
 * Only scripts/integration/mysql-identity.sh may provide the fresh fixture and its ownership marker.
 */
@EnabledIfEnvironmentVariable(named = "UNIPO_DP02_MYSQL_INTEGRATION", matches = "1")
@Timeout(30)
class AccountSecurityMySqlIntegrationTest {
    private static final long SESSION_LIFETIME_MINUTES = 17;
    private static final AtomicInteger PUBLIC_UID = new AtomicInteger(70_000_000);
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static AccountSecurityApi security;
    private static UserMapper users;
    private static DataSourceTransactionManager transactionManager;
    private final List<Long> ownedUsers = new ArrayList<>();

    @BeforeAll
    static void connectOnlyToFreshOwnedFixtureAndApplyAllMigrations() {
        assertEquals("22306", System.getenv("UNIPO_DP02_MYSQL_PORT"),
                "DP02 MySQL tests require the dedicated fixture port");
        String runId = System.getenv("UNIPO_DP02_MYSQL_RUN_ID");
        assertNotNull(runId, "Start the fixture with scripts/integration/mysql-identity.sh");
        assertTrue(runId.matches("[0-9a-f]{16}"), "Invalid MySQL fixture run marker");
        String schema = System.getenv("UNIPO_DP02_MYSQL_SCHEMA");
        assertTrue(("unipo_dp02_" + runId).equals(schema), "MySQL fixture schema does not match this run");
        String password = System.getenv("UNIPO_DP02_MYSQL_PASSWORD");
        assertNotNull(password, "MySQL fixture password must be generated for this run");
        assertTrue(password.matches("[0-9a-f]{48}"), "Invalid generated MySQL fixture password");

        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl("jdbc:mysql://127.0.0.1:22306/" + schema
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                + "&connectTimeout=2000&socketTimeout=5000");
        dataSource.setUsername("unipo_fixture");
        dataSource.setPassword(password);
        jdbc = new JdbcTemplate(dataSource);
        // Every operation before this gate is a read; no schema or application row may be changed first.
        assertTrue(schema.equals(jdbc.queryForObject("SELECT DATABASE()", String.class)),
                "The database connection selected a different schema");
        assertTrue(runId.equals(jdbc.queryForObject("SELECT LOWER(HEX(run_id)) FROM dp02_fixture_marker", String.class)),
                "Refusing to migrate or write without this run's ownership marker");
        assertEquals(List.of("dp02_fixture_marker"), jdbc.queryForList("SHOW TABLES", String.class),
                "A fresh fixture must contain only its ownership marker before Flyway runs");

        // Baseline zero admits only the verified marker table; V1 and every later migration still execute.
        Flyway flyway = Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration").baselineOnMigrate(true).baselineVersion("0")
                .createSchemas(false).cleanDisabled(true).load();
        flyway.migrate();
        assertTrue(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '20' AND success = 1", Integer.class) == 1,
                "The account-security migration must be applied through the complete migration chain");

        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> dataSource);
        context.register(FixtureConfiguration.class);
        context.refresh();
        security = context.getBean(AccountSecurityApi.class);
        users = context.getBean(UserMapper.class);
        transactionManager = context.getBean(DataSourceTransactionManager.class);
        assertTrue(AopUtils.isAopProxy(security), "Integration calls must pass through real transaction advice");
        System.out.println("DP02 MySQL fixture verified; full Flyway migration chain and transaction advice active");
    }

    @AfterEach
    void removeOnlySyntheticUsersCreatedByThisTest() {
        for (Long userId : ownedUsers) {
            jdbc.update("DELETE FROM auth_device_sessions WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    @AfterAll
    static void closeFixtureContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void createsIndependentDevicesWithTheConfiguredAbsoluteLifetime() {
        CurrentUser user = newUser();
        Instant before = Instant.now();
        AccountSecurityApi.ValidatedIdentity first = security.createSession(user);
        AccountSecurityApi.ValidatedIdentity second = security.createSession(user);
        Instant after = Instant.now();

        assertEquals(43, first.user().sessionId().length());
        assertFalse(first.user().sessionId().equals(second.user().sessionId()), "Each device needs its own session");
        assertTrue(security.validate(first.user()).isPresent());
        assertTrue(security.validate(second.user()).isPresent());
        Duration lifetime = Duration.ofMinutes(SESSION_LIFETIME_MINUTES);
        assertFalse(first.expiresAt().isBefore(before.plus(lifetime).minusMillis(1)));
        assertFalse(first.expiresAt().isAfter(after.plus(lifetime)));
        assertTrue(security.validate(first.user()).orElseThrow().expiresAt().equals(first.expiresAt()),
                "Validation must not extend the absolute device-session lifetime");
    }

    @Test
    void singleDeviceRevocationChecksOwnershipAndPreservesOtherDevices() {
        CurrentUser user = newUser();
        CurrentUser other = newUser();
        CurrentUser phone = security.createSession(user).user();
        CurrentUser laptop = security.createSession(user).user();
        CurrentUser otherDevice = security.createSession(other).user();

        security.revokeSession(phone.sessionId(), other.id());
        assertTrue(security.validate(phone).isPresent(), "Another user must not revoke this device");
        security.revokeSession(phone.sessionId(), user.id());

        assertTrue(security.validate(phone).isEmpty());
        assertTrue(security.validate(laptop).isPresent());
        assertTrue(security.validate(otherDevice).isPresent());
        assertEquals(0L, version(user.id()), "Current-device logout must not revoke the other devices by version");
    }

    @Test
    void revokeAllAdvancesVersionAndLeavesAnotherUserAuthenticated() {
        CurrentUser user = newUser();
        CurrentUser other = newUser();
        CurrentUser phone = security.createSession(user).user();
        CurrentUser laptop = security.createSession(user).user();
        CurrentUser otherDevice = security.createSession(other).user();

        security.revokeAll(user.id());

        assertEquals(1L, version(user.id()));
        assertEquals(0L, version(other.id()));
        assertTrue(security.validate(phone).isEmpty());
        assertTrue(security.validate(laptop).isEmpty());
        assertTrue(security.validate(otherDevice).isPresent());
        assertTrue(security.validate(security.createSession(latestUser(user.id())).user()).isPresent(),
                "A newly verified identity at the new version must still be able to sign in");
    }

    @Test
    void cannotCreateADeviceFromCredentialsVerifiedBeforeRevokeAll() {
        CurrentUser expected = newUser();
        security.revokeAll(expected.id());

        assertThrows(AuthenticationException.class, () -> security.createSession(expected));
        assertEquals(0, deviceCount(expected.id()), "A stale verification result must not create any device row");
    }

    @Test
    void disabledAndDeletedAccountsCannotCreateOrValidateSessions() {
        for (String status : List.of("disabled", "deleted")) {
            CurrentUser expected = newUser();
            CurrentUser credential = security.createSession(expected).user();
            jdbc.update("UPDATE users SET status = ? WHERE id = ?", status, expected.id());

            assertTrue(security.validate(credential).isEmpty(), "Inactive accounts must fail every validation");
            assertThrows(AuthenticationException.class, () -> security.createSession(expected));
            assertEquals(1, deviceCount(expected.id()), "Inactive accounts must not create an additional session");
        }
    }

    @Test
    void validationUsesTheLatestStoredRoleAndEmail() {
        CurrentUser expected = newUser();
        CurrentUser credential = security.createSession(expected).user();
        String changedEmail = UUID.randomUUID() + "@example.invalid";
        jdbc.update("UPDATE users SET role = ?, email = ? WHERE id = ?", "OPERATOR", changedEmail, expected.id());

        CurrentUser validated = security.validate(credential).orElseThrow().user();

        assertEquals("OPERATOR", validated.role());
        assertTrue(changedEmail.equals(validated.email()), "Old credential claims must not override current user data");
        assertTrue(credential.sessionId().equals(validated.sessionId()));
        assertEquals(credential.authVersion(), validated.authVersion());
    }

    @Test
    void naturalExpiryCannotValidateEvenWithoutExplicitRevocation() {
        CurrentUser credential = security.createSession(newUser()).user();
        long now = System.currentTimeMillis();
        jdbc.update("UPDATE auth_device_sessions SET created_at_ms = ?, expires_at_ms = ? WHERE session_id = ?",
                now - 20_000, now - 10_000, credential.sessionId());

        assertTrue(security.validate(credential).isEmpty());
    }

    @Test
    void deviceCredentialCannotBeReusedForAnotherUserOrAnotherVersion() {
        CurrentUser credential = security.createSession(newUser()).user();
        CurrentUser other = newUser();
        CurrentUser crossUser = new CurrentUser(other.id(), other.email(), other.role(),
                credential.authVersion(), credential.sessionId());
        CurrentUser wrongVersion = new CurrentUser(credential.id(), credential.email(), credential.role(),
                credential.authVersion() + 1, credential.sessionId());

        assertTrue(security.validate(crossUser).isEmpty());
        assertTrue(security.validate(wrongVersion).isEmpty());
        assertTrue(security.validate(credential).isPresent());
    }

    @Test
    void legacyAndMalformedCredentialsCannotBecomeAuthenticated() {
        CurrentUser expected = newUser();
        CurrentUser legacy = new CurrentUser(expected.id(), expected.email(), expected.role());
        CurrentUser credential = security.createSession(expected).user();
        CurrentUser invalidSession = new CurrentUser(credential.id(), credential.email(), credential.role(),
                credential.authVersion(), "invalid");

        assertThrows(AuthenticationException.class, () -> security.createSession(legacy));
        assertTrue(security.validate(legacy).isEmpty());
        assertTrue(security.validate(expected).isEmpty());
        assertTrue(security.validate(invalidSession).isEmpty());
        assertTrue(security.validate(null).isEmpty());
    }

    @Test
    void staleUserMapperUpdateCannotRollBackSecurityFieldsOrAuthenticationVersion() {
        CurrentUser expected = newUser();
        CurrentUser credential = security.createSession(expected).user();
        User stale = users.selectById(expected.id());
        assertEquals(0L, stale.getAuthVersion());
        String newHash = UUID.randomUUID().toString();
        jdbc.update("UPDATE users SET password_hash = ?, role = ?, status = ?, operator_scope = ? WHERE id = ?",
                newHash, "OPERATOR", "disabled", "fixture-restricted-scope", expected.id());
        security.revokeAll(expected.id());
        stale.setNickname("updated-" + UUID.randomUUID());
        stale.setAuthVersion(0L);

        assertEquals(1, users.updateById(stale));

        assertEquals(1L, version(expected.id()), "A stale profile entity must never restore an old authentication version");
        assertTrue(stale.getNickname().equals(jdbc.queryForObject("SELECT nickname FROM users WHERE id = ?",
                String.class, expected.id())), "The profile update itself should still succeed");
        assertTrue(newHash.equals(jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?",
                String.class, expected.id())), "A stale profile update must not restore the old password hash");
        assertEquals("OPERATOR", jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, expected.id()));
        assertEquals("disabled", jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, expected.id()));
        assertEquals("fixture-restricted-scope", jdbc.queryForObject(
                "SELECT operator_scope FROM users WHERE id = ?", String.class, expected.id()));
        assertTrue(security.validate(credential).isEmpty());
    }

    @Test
    void revokeAllRollsBackWithItsEnclosingBusinessTransaction() {
        CurrentUser expected = newUser();
        CurrentUser phone = security.createSession(expected).user();
        CurrentUser laptop = security.createSession(expected).user();
        TransactionTemplate business = new TransactionTemplate(transactionManager);

        business.executeWithoutResult(transaction -> {
            security.revokeAll(expected.id());
            assertEquals(1L, version(expected.id()), "The outer transaction must see the pending version change");
            transaction.setRollbackOnly();
        });

        assertEquals(0L, version(expected.id()), "The version advance must roll back with the business operation");
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth_device_sessions WHERE user_id = ? AND revoked_at_ms IS NULL",
                Integer.class, expected.id()));
        assertTrue(security.validate(phone).isPresent());
        assertTrue(security.validate(laptop).isPresent());
    }

    @Test
    void concurrentCreateAndRevokeAllCannotLeaveAnOldPrincipalValid() throws Exception {
        CurrentUser expected = newUser();
        CurrentUser existing = security.createSession(expected).user();
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<CurrentUser> creating = workers.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                try {
                    return security.createSession(expected).user();
                } catch (AuthenticationException rejectedAfterRevocation) {
                    return null;
                }
            });
            Future<?> revoking = workers.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                security.revokeAll(expected.id());
                return null;
            });
            CurrentUser racedCredential = creating.get(10, TimeUnit.SECONDS);
            revoking.get(10, TimeUnit.SECONDS);

            assertEquals(1L, version(expected.id()));
            assertTrue(security.validate(existing).isEmpty());
            if (racedCredential != null) {
                assertTrue(security.validate(racedCredential).isEmpty(),
                        "A device created concurrently from the old version must not survive revoke-all");
            }
            assertTrue(security.validate(security.createSession(latestUser(expected.id())).user()).isPresent());
        } finally {
            stopWorkers(workers);
        }
    }

    @Test
    void anExistingRepeatableReadSnapshotCannotAuthorizeARevokedDevice() {
        CurrentUser expected = newUser();
        CurrentUser credential = security.createSession(expected).user();
        TransactionTemplate business = new TransactionTemplate(transactionManager);
        business.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            business.executeWithoutResult(transaction -> {
                assertEquals(0L, version(expected.id()), "Establish the business transaction's old read snapshot");
                assertTrue(security.validate(credential).isPresent());
                Future<?> revoked = worker.submit(() -> security.revokeAll(expected.id()));
                waitFor(revoked);
                assertEquals(0L, version(expected.id()), "The business snapshot should still hold the old version");
                assertTrue(security.validate(credential).isEmpty(),
                        "Authentication must not reuse the business snapshot or a previous positive mapper result");
            });
            assertEquals(1L, version(expected.id()));
        } finally {
            stopWorkers(worker);
        }
    }

    private CurrentUser newUser() {
        String email = UUID.randomUUID() + "@example.invalid";
        jdbc.update(
                "INSERT INTO users (public_uid, email, password_hash, nickname, role, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'USER', 'active', NOW(), NOW())",
                Integer.toString(PUBLIC_UID.getAndIncrement()), email, UUID.randomUUID().toString(),
                "dp02-" + UUID.randomUUID());
        Long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        assertNotNull(id);
        ownedUsers.add(id);
        return latestUser(id);
    }

    private CurrentUser latestUser(long id) {
        return jdbc.queryForObject("SELECT id, email, role, auth_version FROM users WHERE id = ?",
                (row, index) -> new CurrentUser(row.getLong("id"), row.getString("email"),
                        row.getString("role"), row.getLong("auth_version"), null), id);
    }

    private long version(long userId) {
        Long value = jdbc.queryForObject("SELECT auth_version FROM users WHERE id = ?", Long.class, userId);
        assertNotNull(value);
        return value;
    }

    private int deviceCount(long userId) {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM auth_device_sessions WHERE user_id = ?", Integer.class, userId);
        assertNotNull(value);
        return value;
    }

    private static void waitFor(Future<?> task) {
        try {
            task.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the fixture transaction", interrupted);
        } catch (ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new IllegalStateException("The fixture transaction did not complete", failure);
        }
    }

    private static void stopWorkers(ExecutorService workers) {
        workers.shutdownNow();
        try {
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "Fixture transaction workers must stop before cleanup");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping fixture workers", interrupted);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class FixtureConfiguration {
        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setDefaultStatementTimeout(5);
            configuration.addMapper(DeviceSessionMapper.class);
            configuration.addMapper(UserMapper.class);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        DeviceSessionMapper deviceSessionMapper(SqlSessionTemplate template) {
            return template.getMapper(DeviceSessionMapper.class);
        }

        @Bean
        UserMapper userMapper(SqlSessionTemplate template) {
            return template.getMapper(UserMapper.class);
        }

        @Bean
        DataSourceTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        AccountSecurityService accountSecurityService(DeviceSessionMapper mapper) {
            AppProperties properties = new AppProperties(null,
                    new AppProperties.Jwt(null, 120, SESSION_LIFETIME_MINUTES), null, null, null, null, null);
            return new AccountSecurityService(mapper, properties);
        }
    }
}
