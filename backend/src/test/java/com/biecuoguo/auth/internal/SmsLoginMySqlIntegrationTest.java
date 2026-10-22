package com.biecuoguo.auth.internal;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.biecuoguo.auth.api.AccountSecurityApi;
import com.biecuoguo.config.AppProperties;
import com.biecuoguo.config.AppTime;
import com.biecuoguo.domain.SmsCode;
import com.biecuoguo.domain.User;
import com.biecuoguo.dto.AdminDtos;
import com.biecuoguo.dto.AuthDtos;
import com.biecuoguo.mapper.SmsCodeMapper;
import com.biecuoguo.mapper.UserMapper;
import com.biecuoguo.mapper.UserPreferenceMapper;
import com.biecuoguo.post.api.PostQueryApi;
import com.biecuoguo.security.CurrentUser;
import com.biecuoguo.security.JwtService;
import com.biecuoguo.security.SessionService;
import com.biecuoguo.security.TokenBlacklistService;
import com.biecuoguo.service.AdminService;
import com.biecuoguo.service.AuthService;
import com.biecuoguo.service.PresenceService;
import com.biecuoguo.service.SmsService;
import com.biecuoguo.service.VerificationService;
import com.biecuoguo.state.MemorySessionStore;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Real SMS-login transactions with gates around verification, account reads, and session creation. */
@EnabledIfEnvironmentVariable(named = "UNIPO_DP02_MYSQL_INTEGRATION", matches = "1")
@Timeout(30)
class SmsLoginMySqlIntegrationTest {
    private static final ThreadLocal<Plan> LOGIN_PLAN = new ThreadLocal<>();
    private static final ThreadLocal<CyclicBarrier> SMS_VERIFY_BARRIER = new ThreadLocal<>();
    private static final AtomicInteger PUBLIC_UID = new AtomicInteger(71_000_000);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static AuthService auth;
    private static AuthService realAuth;
    private static AdminService admin;
    private static SessionService sessions;
    private static AccountSecurityApi authority;
    private static JwtService jwt;
    private static PasswordEncoder encoder;
    private static SmsService realSms;
    private final List<String> ownedPhones = new ArrayList<>();
    private final ExecutorService workers = Executors.newFixedThreadPool(3);

    enum Point { BEFORE_VERIFY, BEFORE_ACCOUNT_READ, AFTER_ACCOUNT_READ, AFTER_LOGIN_WRITE, AFTER_CHALLENGE_READ }
    enum Mutation { LOGOUT_ALL, DISABLE, ROLE_DOWNGRADE }

    @BeforeAll
    static void initializeOnlyOwnedMySql() {
        String run = System.getenv("UNIPO_DP02_MYSQL_RUN_ID");
        assertNotNull(run, "Use the dedicated SMS MySQL fixture runner");
        assertTrue(run.matches("[0-9a-f]{16}"), "Invalid owned fixture marker");
        assertEquals("22306", System.getenv("UNIPO_DP02_MYSQL_PORT"));
        assertEquals("unipo_dp02_" + run, System.getenv("UNIPO_DP02_MYSQL_SCHEMA"));
        String password = System.getenv("UNIPO_DP02_MYSQL_PASSWORD");
        assertNotNull(password);
        assertTrue(password.matches("[0-9a-f]{48}"), "Invalid generated fixture password");
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl("jdbc:mysql://127.0.0.1:22306/unipo_dp02_" + run
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=2000&socketTimeout=10000");
        dataSource.setUsername("unipo_fixture");
        dataSource.setPassword(password);
        jdbc = new JdbcTemplate(dataSource);
        assertEquals("unipo_dp02_" + run, jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertTrue(run.equals(jdbc.queryForObject("SELECT LOWER(HEX(run_id)) FROM dp02_fixture_marker", String.class)),
                "Refusing migration or writes without this run marker");
        assertEquals(List.of("dp02_fixture_marker"), jdbc.queryForList("SHOW TABLES", String.class));
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("0").cleanDisabled(true).createSchemas(false).load().migrate();
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> dataSource);
        context.register(FixtureConfiguration.class);
        context.refresh();
        auth = context.getBean("mockAuth", AuthService.class);
        realAuth = context.getBean("realAuth", AuthService.class);
        admin = context.getBean(AdminService.class);
        sessions = context.getBean(SessionService.class);
        authority = context.getBean("authority", AccountSecurityApi.class);
        jwt = context.getBean(JwtService.class);
        encoder = context.getBean(PasswordEncoder.class);
        realSms = context.getBean("realSms", SmsService.class);
        assertTrue(AopUtils.isAopProxy(auth), "SMS login must use its actual transaction boundary");
        assertTrue(AopUtils.isAopProxy(authority), "Device sessions must use actual transactions");
    }

    @AfterEach
    void cleanOnlyOwnedRows() throws InterruptedException {
        workers.shutdownNow();
        assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "Gate workers must finish before row cleanup");
        for (String phone : ownedPhones) {
            jdbc.update("DELETE FROM sms_codes WHERE phone = ?", phone);
            for (Long id : jdbc.queryForList("SELECT id FROM users WHERE phone = ? OR email = ?", Long.class, phone, phone)) {
                jdbc.update("DELETE FROM auth_device_sessions WHERE user_id = ?", id);
                jdbc.update("DELETE FROM user_preferences WHERE user_id = ?", id);
                jdbc.update("DELETE FROM users WHERE id = ?", id);
            }
        }
    }

    @AfterAll
    static void closeContext() {
        if (context != null) context.close();
    }

    static Stream<Arguments> beforeWriteInterleavings() {
        return Stream.of(Point.BEFORE_VERIFY, Point.BEFORE_ACCOUNT_READ, Point.AFTER_ACCOUNT_READ)
                .flatMap(point -> Stream.of(Mutation.values()).map(action -> Arguments.of(point, action)));
    }

    @ParameterizedTest(name = "{0} with {1}")
    @MethodSource("beforeWriteInterleavings")
    void securityMutationCannotUpgradeACapturedOldAccountVersion(Point point, Mutation mutation) throws Exception {
        String phone = newPhone();
        CurrentUser initial = existingUser(phone, false);
        CurrentUser oldDevice = authority.createSession(initial).user();
        Plan plan = new Plan(point, null);
        Future<Attempt> loggingIn = loginInWorker(phone, plan);
        try {
            plan.awaitReached();
            mutate(initial.id(), mutation);
        } finally {
            plan.release();
        }
        Attempt result = loggingIn.get(12, TimeUnit.SECONDS);
        assertEquals(1L, version(initial.id()), "The mutation must commit before the gate is released");
        assertTrue(authority.validate(oldDevice).isEmpty());
        boolean shouldSucceed = mutation != Mutation.DISABLE && point != Point.AFTER_ACCOUNT_READ;
        System.out.printf("SMS gate=%s action=%s success=%s failureType=%s%n", point, mutation,
                result.succeeded(), result.failureType());
        assertEquals(shouldSucceed, result.succeeded(),
                "Only an active account first read after the committed mutation may create a new-version login");
        if (shouldSucceed) {
            CurrentUser issued = jwt.parse(result.response().token());
            assertEquals(1L, issued.authVersion());
            assertEquals(mutation == Mutation.ROLE_DOWNGRADE ? "USER" : "OPERATOR", issued.role());
            assertTrue(authority.validate(issued).isPresent());
        } else {
            assertEquals(1, deviceCount(initial.id()), "No additional device may survive the rejected transaction");
        }
    }

    @ParameterizedTest(name = "locked login serializes before {0}")
    @EnumSource(Mutation.class)
    void aLoginHoldingTheUserWriteLockMayFinishBeforeTheRevocationCommit(Mutation mutation) throws Exception {
        String phone = newPhone();
        CurrentUser initial = existingUser(phone, false);
        Plan plan = new Plan(Point.AFTER_LOGIN_WRITE, null);
        Future<Attempt> loggingIn = loginInWorker(phone, plan);
        CountDownLatch mutationStarted = new CountDownLatch(1);
        Future<?> mutationTask = null;
        try {
            plan.awaitReached();
            mutationTask = workers.submit(() -> {
                mutationStarted.countDown();
                mutate(initial.id(), mutation);
            });
            assertTrue(mutationStarted.await(5, TimeUnit.SECONDS));
        } finally {
            plan.release();
        }
        Attempt result = loggingIn.get(12, TimeUnit.SECONDS);
        assertNotNull(mutationTask);
        mutationTask.get(12, TimeUnit.SECONDS);
        assertTrue(result.succeeded(), "The login already holding the row write lock may linearize first");
        CurrentUser issued = jwt.parse(result.response().token());
        assertEquals(0L, issued.authVersion());
        assertEquals(1L, version(initial.id()));
        assertTrue(authority.validate(issued).isEmpty(), "After revocation commits, even this just-issued identity must fail");
        System.out.printf("SMS gate=AFTER_LOGIN_WRITE action=%s loginCompleted=true identityValidAfterMutation=false%n", mutation);
    }

    @Test
    void accountFoundByPhonePreservesTheExistingIdentity() {
        String phone = newPhone();
        CurrentUser initial = existingUser(phone, false);
        Attempt result = attemptLogin(phone, null);
        assertTrue(result.succeeded());
        assertEquals(initial.id(), result.response().user().id());
        assertTrue(authority.validate(jwt.parse(result.response().token())).isPresent());
    }

    @Test
    void emailEqualToPhoneFallbackMustPersistThePhoneBinding() {
        String phone = newPhone();
        CurrentUser initial = existingUser(phone, true);
        Attempt result = attemptLogin(phone, null);
        assertTrue(result.succeeded(), "The compatibility account must remain able to sign in");
        assertEquals(initial.id(), result.response().user().id());
        boolean phonePersisted = phone.equals(jdbc.queryForObject("SELECT phone FROM users WHERE id = ?", String.class, initial.id()));
        System.out.printf("SMS email-phone fallback loginSucceeded=true phoneBindingPersisted=%s%n", phonePersisted);
        assertTrue(phonePersisted, "A successful email==phone fallback login must persist its phone binding");
    }

    @Test
    void fallbackPhoneBindingCannotUpgradeAnAccountVersionAlreadyReadBeforeRevocation() throws Exception {
        String phone = newPhone();
        CurrentUser initial = existingUser(phone, true);
        Plan plan = new Plan(Point.AFTER_ACCOUNT_READ, null);
        plan.accountReadTarget = 2; // First phone lookup is empty; the second lookup captures email==phone's version.
        Future<Attempt> loggingIn = loginInWorker(phone, plan);
        try {
            plan.awaitReached();
            sessions.revokeAllForUser(initial.id());
        } finally {
            plan.release();
        }
        assertFalse(loggingIn.get(12, TimeUnit.SECONDS).succeeded());
        assertEquals(1L, version(initial.id()));
        assertNull(jdbc.queryForObject("SELECT phone FROM users WHERE id = ?", String.class, initial.id()));
        assertEquals(0, deviceCount(initial.id()));
    }

    @Test
    void realChallengeConsumptionRollsBackWhenRevocationWinsAfterAccountRead() throws Exception {
        String phone = newPhone();
        String code = String.format("%04d", RANDOM.nextInt(10_000));
        CurrentUser initial = existingUser(phone, false);
        seedChallenge(phone, code);
        Plan plan = new Plan(Point.AFTER_ACCOUNT_READ, null);
        Future<Attempt> loggingIn = realLoginInWorker(phone, code, plan);
        try {
            plan.awaitReached();
            sessions.revokeAllForUser(initial.id());
        } finally {
            plan.release();
        }

        Attempt result = loggingIn.get(12, TimeUnit.SECONDS);
        assertFalse(result.succeeded(), "A login that captured the old auth version must not survive revocation");
        assertEquals(1L, version(initial.id()));
        assertEquals(0, deviceCount(initial.id()), "No device may be created from the stale account snapshot");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sms_codes WHERE phone = ? AND consumed = 1",
                Integer.class, phone), "The failed transaction must roll back single-use challenge consumption");
        System.out.println("SMS real-challenge revocation race rejected=true challengeConsumptionRolledBack=true");
    }

    @Test
    void concurrentFirstLoginsDoNotDuplicateAccountsButExposeAnyUnretriedUniqueConflict() throws Exception {
        String phone = newPhone();
        CyclicBarrier inserts = new CyclicBarrier(2);
        Future<Attempt> first = loginInWorker(phone, new Plan(null, inserts));
        Future<Attempt> second = loginInWorker(phone, new Plan(null, inserts));
        Attempt one = first.get(15, TimeUnit.SECONDS);
        Attempt two = second.get(15, TimeUnit.SECONDS);
        int successes = (one.succeeded() ? 1 : 0) + (two.succeeded() ? 1 : 0);
        int rows = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE phone = ? OR email = ?", Integer.class, phone, phone);
        assertEquals(1, rows, "Unique indexes must prevent duplicate identities");
        long userId = jdbc.queryForObject("SELECT id FROM users WHERE phone = ? OR email = ?", Long.class, phone, phone);
        assertEquals(successes, deviceCount(userId), "A failed transaction must not leave a device session behind");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_preferences WHERE user_id = ?", Integer.class, userId));
        assertTrue(successes >= 1, "At least one first login must complete");
        System.out.printf("SMS concurrent-first-login userRows=%d successes=%d failureTypes=%s,%s%n", rows, successes,
                one.failureType(), two.failureType());
        assertEquals(2, successes, "Concurrent valid first logins should converge on the unique account, rather than leak a raw write conflict");
        assertEquals(one.response().user().id(), two.response().user().id());
        CurrentUser firstDevice = jwt.parse(one.response().token());
        CurrentUser secondDevice = jwt.parse(two.response().token());
        assertFalse(firstDevice.sessionId().equals(secondDevice.sessionId()), "Each successful login needs an independent device");
        assertTrue(authority.validate(firstDevice).isPresent());
        assertTrue(authority.validate(secondDevice).isPresent());
    }

    @Test
    void aStoredSmsChallengeCanBeConsumedByOnlyOneConcurrentVerifier() throws Exception {
        String phone = newPhone();
        String code = String.format("%04d", RANDOM.nextInt(10_000));
        seedChallenge(phone, code);
        CyclicBarrier alreadyReadChallenge = new CyclicBarrier(2);
        Future<String> first = workers.submit(() -> verifyStored(phone, code, alreadyReadChallenge, null));
        Future<String> second = workers.submit(() -> verifyStored(phone, code, alreadyReadChallenge, null));
        String firstFailure = first.get(12, TimeUnit.SECONDS);
        String secondFailure = second.get(12, TimeUnit.SECONDS);
        int successes = (firstFailure == null ? 1 : 0) + (secondFailure == null ? 1 : 0);
        assertEquals(1, successes, "A stored single-use challenge must authorize only one verifier");
        String rejected = firstFailure == null ? secondFailure : firstFailure;
        assertEquals("IllegalArgumentException", rejected);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sms_codes WHERE phone = ? AND consumed = 1", Integer.class, phone));
        System.out.println("SMS stored-challenge concurrentVerifiers=2 successes=1 rejected=1 consumedRows=1");
    }

    @Test
    void aChallengeExpiringAfterItWasReadCannotBeConsumed() throws Exception {
        String phone = newPhone();
        String code = String.format("%04d", RANDOM.nextInt(10_000));
        seedChallenge(phone, code);
        Plan plan = new Plan(Point.AFTER_CHALLENGE_READ, null);
        Future<String> verifying = workers.submit(() -> verifyStored(phone, code, null, plan));
        try {
            plan.awaitReached();
            jdbc.update("UPDATE sms_codes SET expires_at = ? WHERE phone = ?", AppTime.nowLocal().minusSeconds(1), phone);
        } finally {
            plan.release();
        }
        assertEquals("IllegalArgumentException", verifying.get(12, TimeUnit.SECONDS));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sms_codes WHERE phone = ? AND consumed = 1", Integer.class, phone));
    }

    @Test
    void aRetriedLoginRollsBackSmsConsumptionAndAccountCreationBeforeUsingANewTransaction() {
        String phone = newPhone();
        String code = String.format("%04d", RANDOM.nextInt(10_000));
        seedChallenge(phone, code);
        Plan plan = new Plan(null, null);
        plan.createFailures.set(1);
        LOGIN_PLAN.set(plan);
        try {
            AuthDtos.AuthResponse result = realAuth.smsLogin(new AuthDtos.SmsLoginRequest(phone, code, "sms-" + UUID.randomUUID()));
            assertTrue(authority.validate(jwt.parse(result.token())).isPresent());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE phone = ?", Integer.class, phone));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_preferences WHERE user_id = ?", Integer.class, result.user().id()));
            assertEquals(1, deviceCount(result.user().id()));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sms_codes WHERE phone = ? AND consumed = 1", Integer.class, phone));
            assertEquals(2, plan.createCalls.get(), "The second whole transaction must re-verify the rolled-back challenge");
        } finally {
            LOGIN_PLAN.remove();
        }
        System.out.println("SMS retry rolledBackChallenge=true userRows=1 preferenceRows=1 deviceRows=1 consumedRows=1");
    }

    private void seedChallenge(String phone, String code) {
        jdbc.update("INSERT INTO sms_codes (phone, purpose, code_hash, expires_at, consumed, created_at) VALUES (?, 'login', ?, ?, 0, ?)",
                phone, encoder.encode(code), AppTime.nowLocal().plusMinutes(2), AppTime.nowLocal());
    }

    private static String verifyStored(String phone, String code, CyclicBarrier barrier, Plan plan) {
        SMS_VERIFY_BARRIER.set(barrier);
        LOGIN_PLAN.set(plan);
        try {
            realSms.verify(phone, code);
            return null;
        } catch (Throwable failure) {
            return failure.getClass().getSimpleName();
        } finally {
            SMS_VERIFY_BARRIER.remove();
            LOGIN_PLAN.remove();
        }
    }

    private Future<Attempt> loginInWorker(String phone, Plan plan) {
        return workers.submit(() -> attemptLogin(phone, plan));
    }

    private Future<Attempt> realLoginInWorker(String phone, String code, Plan plan) {
        return workers.submit(() -> {
            LOGIN_PLAN.set(plan);
            try {
                return new Attempt(realAuth.smsLogin(new AuthDtos.SmsLoginRequest(phone, code,
                        "sms-real-race-" + UUID.randomUUID())), null);
            } catch (Throwable failure) {
                return new Attempt(null, failure.getClass().getSimpleName());
            } finally {
                LOGIN_PLAN.remove();
            }
        });
    }

    private Attempt attemptLogin(String phone, Plan plan) {
        LOGIN_PLAN.set(plan);
        try {
            String code = String.format("%04d", RANDOM.nextInt(10_000));
            return new Attempt(auth.smsLogin(new AuthDtos.SmsLoginRequest(phone, code, "sms-" + UUID.randomUUID())), null);
        } catch (Throwable failure) {
            // Never retain/print the SQL cause: it can include the synthetic phone, hash, or code.
            return new Attempt(null, failure.getClass().getSimpleName());
        } finally {
            LOGIN_PLAN.remove();
        }
    }

    private void mutate(long id, Mutation action) {
        switch (action) {
            case LOGOUT_ALL -> sessions.revokeAllForUser(id);
            case DISABLE -> admin.updateUserStatus(id, new AdminDtos.UserStatusRequest("disabled"));
            case ROLE_DOWNGRADE -> admin.updateUserRole(id, new AdminDtos.UserRoleRequest("USER", "student"));
        }
    }

    private String newPhone() {
        String phone = "199" + String.format("%08d", RANDOM.nextInt(100_000_000));
        ownedPhones.add(phone);
        return phone;
    }

    private CurrentUser existingUser(String phone, boolean emailFallback) {
        jdbc.update("INSERT INTO users (public_uid, email, phone, password_hash, nickname, role, operator_scope, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'OPERATOR', 'school', 'active', NOW(), NOW())",
                Integer.toString(PUBLIC_UID.getAndIncrement()), phone, emailFallback ? null : phone,
                encoder.encode(UUID.randomUUID().toString()), "sms-" + UUID.randomUUID());
        long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, phone);
        jdbc.update("INSERT INTO user_preferences (user_id, created_at, updated_at) VALUES (?, NOW(), NOW())", id);
        return new CurrentUser(id, phone, "OPERATOR", 0L, null);
    }

    private long version(long id) {
        return jdbc.queryForObject("SELECT auth_version FROM users WHERE id = ?", Long.class, id);
    }

    private int deviceCount(long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM auth_device_sessions WHERE user_id = ?", Integer.class, id);
    }

    private record Attempt(AuthDtos.AuthResponse response, String failureType) {
        boolean succeeded() { return response != null; }
        @Override public String toString() { return succeeded() ? "Attempt[success]" : "Attempt[failed]"; }
    }

    private static final class Plan {
        final Point point;
        final CyclicBarrier insertBarrier;
        final AtomicInteger accountReads = new AtomicInteger();
        int accountReadTarget = 1;
        final AtomicBoolean firstInsert = new AtomicBoolean();
        final AtomicInteger createFailures = new AtomicInteger();
        final AtomicInteger createCalls = new AtomicInteger();
        final CountDownLatch reached = new CountDownLatch(1);
        final CountDownLatch resume = new CountDownLatch(1);
        Plan(Point point, CyclicBarrier insertBarrier) { this.point = point; this.insertBarrier = insertBarrier; }
        void pause(Point current) throws InterruptedException {
            if (point == current) {
                reached.countDown();
                assertTrue(resume.await(10, TimeUnit.SECONDS), "The controlled login gate was not released");
            }
        }
        void awaitReached() throws InterruptedException { assertTrue(reached.await(8, TimeUnit.SECONDS), "The login did not reach the gate"); }
        void release() { resume.countDown(); }
    }

    private static final class GateAccountApi implements AccountSecurityApi {
        private final AccountSecurityApi delegate;
        GateAccountApi(AccountSecurityApi delegate) { this.delegate = delegate; }
        public ValidatedIdentity createSession(CurrentUser expected) {
            Plan plan = LOGIN_PLAN.get();
            try { if (plan != null) plan.pause(Point.AFTER_LOGIN_WRITE); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Fixture gate interrupted"); }
            if (plan != null) {
                plan.createCalls.incrementAndGet();
                if (plan.createFailures.getAndDecrement() > 0)
                    throw new org.springframework.dao.DuplicateKeyException("fixture-only retryable write race");
            }
            return delegate.createSession(expected);
        }
        public Optional<ValidatedIdentity> validate(CurrentUser user) { return delegate.validate(user); }
        public void revokeSession(String sid, long id) { delegate.revokeSession(sid, id); }
        public void revokeAll(long id) { delegate.revokeAll(id); }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class FixtureConfiguration {
        @Bean AppProperties properties() {
            return new AppProperties(null, new AppProperties.Jwt("fixture-only-" + UUID.randomUUID(), 5, 17),
                    null, null, null, new AppProperties.Auth("sid", "csrf", "X-CSRF-Token", 5, false, "Lax"), 1L);
        }
        @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
        @Bean JwtService jwt(AppProperties properties) { return new JwtService(properties); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setDefaultStatementTimeout(8);
            for (Class<?> mapper : List.of(DeviceSessionMapper.class, UserMapper.class, UserPreferenceMapper.class, SmsCodeMapper.class))
                configuration.addMapper(mapper);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            return factory.getObject();
        }
        @Bean SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) { return new SqlSessionTemplate(factory); }
        @Bean DataSourceTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean DeviceSessionMapper devices(SqlSessionTemplate template) { return template.getMapper(DeviceSessionMapper.class); }
        @Bean("realUsers") UserMapper realUsers(SqlSessionTemplate template) { return template.getMapper(UserMapper.class); }
        @Bean UserPreferenceMapper preferences(SqlSessionTemplate template) { return template.getMapper(UserPreferenceMapper.class); }
        @Bean("authority") AccountSecurityService authority(DeviceSessionMapper mapper, AppProperties properties) {
            return new AccountSecurityService(mapper, properties);
        }
        @Bean("loginAccounts") AccountSecurityApi loginAccounts(@Qualifier("authority") AccountSecurityApi authority) {
            return new GateAccountApi(authority);
        }
        @Bean SessionService sessions(AppProperties properties, @Qualifier("authority") AccountSecurityApi authority) {
            return new SessionService(new MemorySessionStore(), properties, authority);
        }
        @Bean AdminService admin(@Qualifier("realUsers") UserMapper users, SessionService sessions) {
            return new AdminService(users, mock(PostQueryApi.class), sessions);
        }
        @Bean("loginUsers") @SuppressWarnings("unchecked")
        UserMapper loginUsers(@Qualifier("realUsers") UserMapper real) {
            UserMapper decorated = mock(UserMapper.class, delegatesTo(real));
            doAnswer(invocation -> {
                Plan plan = LOGIN_PLAN.get();
                boolean first = plan != null && plan.accountReads.incrementAndGet() == plan.accountReadTarget;
                if (first) plan.pause(Point.BEFORE_ACCOUNT_READ);
                User value = real.selectOne((Wrapper<User>) invocation.getArgument(0));
                if (first) plan.pause(Point.AFTER_ACCOUNT_READ);
                return value;
            }).when(decorated).selectOne(any(Wrapper.class));
            doAnswer(invocation -> {
                Plan plan = LOGIN_PLAN.get();
                if (plan != null && plan.insertBarrier != null && plan.firstInsert.compareAndSet(false, true))
                    plan.insertBarrier.await(8, TimeUnit.SECONDS);
                return real.insert((User) invocation.getArgument(0));
            }).when(decorated).insert(any(User.class));
            return decorated;
        }
        @Bean("realSms") @SuppressWarnings("unchecked")
        SmsService realSms(PasswordEncoder encoder, SqlSessionTemplate template) {
            SmsCodeMapper real = template.getMapper(SmsCodeMapper.class);
            SmsCodeMapper decorated = mock(SmsCodeMapper.class, delegatesTo(real));
            doAnswer(invocation -> {
                SmsCode value = real.selectOne((Wrapper<SmsCode>) invocation.getArgument(0));
                CyclicBarrier barrier = SMS_VERIFY_BARRIER.get();
                if (barrier != null) barrier.await(8, TimeUnit.SECONDS);
                Plan plan = LOGIN_PLAN.get();
                if (plan != null) plan.pause(Point.AFTER_CHALLENGE_READ);
                return value;
            }).when(decorated).selectOne(any(Wrapper.class));
            return new SmsService(decorated, encoder, false);
        }
        @Bean("mockSms") SmsService sms(PasswordEncoder encoder) {
            SmsService sms = spy(new SmsService(mock(SmsCodeMapper.class), encoder, true));
            doAnswer(invocation -> {
                Plan plan = LOGIN_PLAN.get();
                if (plan != null) plan.pause(Point.BEFORE_VERIFY);
                return invocation.callRealMethod();
            }).when(sms).verify(anyString(), anyString());
            return sms;
        }
        @Bean("mockAuth") AuthService auth(AppProperties properties, @Qualifier("loginUsers") UserMapper users,
                               UserPreferenceMapper preferences, PasswordEncoder encoder, JwtService jwt,
                               @Qualifier("mockSms") SmsService sms, @Qualifier("loginAccounts") AccountSecurityApi accounts,
                               SessionService sessions, PlatformTransactionManager transactionManager) {
            return new AuthService(properties, users, preferences, mock(VerificationService.class), encoder, jwt,
                    mock(TokenBlacklistService.class), mock(PresenceService.class), sms, accounts, sessions, transactionManager);
        }
        @Bean("realAuth") AuthService realAuth(AppProperties properties, @Qualifier("loginUsers") UserMapper users,
                               UserPreferenceMapper preferences, PasswordEncoder encoder, JwtService jwt,
                               @Qualifier("realSms") SmsService sms, @Qualifier("loginAccounts") AccountSecurityApi accounts,
                               SessionService sessions, PlatformTransactionManager transactionManager) {
            return new AuthService(properties, users, preferences, mock(VerificationService.class), encoder, jwt,
                    mock(TokenBlacklistService.class), mock(PresenceService.class), sms, accounts, sessions, transactionManager);
        }
    }
}
