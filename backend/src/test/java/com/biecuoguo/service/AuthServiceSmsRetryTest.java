package com.biecuoguo.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.biecuoguo.auth.api.AccountSecurityApi;
import com.biecuoguo.config.AppProperties;
import com.biecuoguo.domain.User;
import com.biecuoguo.domain.UserPreference;
import com.biecuoguo.dto.AuthDtos;
import com.biecuoguo.mapper.UserMapper;
import com.biecuoguo.mapper.UserPreferenceMapper;
import com.biecuoguo.security.CurrentUser;
import com.biecuoguo.security.JwtService;
import com.biecuoguo.security.SessionService;
import com.biecuoguo.security.TokenBlacklistService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthServiceSmsRetryTest {
    private static final String PHONE = "13900003333";
    private static final String CODE = "2849";
    private static final long USER_ID = 42L;

    @BeforeAll
    static void initializeLambdaMetadata() {
        for (Class<?> type : new Class<?>[]{User.class, UserPreference.class}) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "sms-retry-tests"), type);
        }
    }

    @Test
    void duplicateKeyRestartsVerificationAndAccountReadInANewTransaction() {
        Fixture fixture = new Fixture();
        when(fixture.users.update(isNull(), any()))
                .thenThrow(new DuplicateKeyException("fixture-duplicate"))
                .thenReturn(1);

        AuthDtos.AuthResponse result = fixture.login();

        assertEquals(USER_ID, result.user().id());
        fixture.assertAttempts(2, 1, 1);
        assertEquals(List.of(1, 2), fixture.verificationTransactions);
        assertEquals(List.of(1, 2), fixture.accountReadTransactions);
        verify(fixture.sms, times(2)).verify(PHONE, CODE);
        verify(fixture.users, times(2)).selectOne(any());
        verify(fixture.accounts).createSession(any(CurrentUser.class));
    }

    @Test
    void transientFailureLateInAnAttemptRepeatsTheCompleteTransaction() {
        Fixture fixture = new Fixture();
        doThrow(new CannotAcquireLockException("fixture-transient"))
                .doAnswer(call -> fixture.identity(call.getArgument(0)))
                .when(fixture.accounts).createSession(any(CurrentUser.class));

        AuthDtos.AuthResponse result = fixture.login();

        assertEquals(USER_ID, result.user().id());
        fixture.assertAttempts(2, 1, 1);
        assertEquals(List.of(1, 2), fixture.verificationTransactions);
        assertEquals(List.of(1, 2), fixture.accountReadTransactions);
        verify(fixture.sms, times(2)).verify(PHONE, CODE);
        verify(fixture.users, times(2)).update(isNull(), any());
        verify(fixture.preferences, times(2)).selectOne(any());
        verify(fixture.accounts, times(2)).createSession(any(CurrentUser.class));
        verify(fixture.jwt).createToken(any(CurrentUser.class), any(Instant.class));
        verify(fixture.jwt).createRefreshToken(any(CurrentUser.class), any(Instant.class));
    }

    @Test
    void mixedRetryableFailuresCanSucceedOnlyInTheThirdFreshTransaction() {
        Fixture fixture = new Fixture();
        when(fixture.users.update(isNull(), any()))
                .thenThrow(new DuplicateKeyException("fixture-first"))
                .thenThrow(new CannotAcquireLockException("fixture-second"))
                .thenReturn(1);

        fixture.login();

        fixture.assertAttempts(3, 1, 2);
        assertEquals(List.of(1, 2, 3), fixture.verificationTransactions);
        assertEquals(List.of(1, 2, 3), fixture.accountReadTransactions);
        verify(fixture.sms, times(3)).verify(PHONE, CODE);
        verify(fixture.accounts).createSession(any(CurrentUser.class));
    }

    @Test
    void threeRetryableFailuresAreTheLimitAndTheLastFailureIsPreserved() {
        Fixture fixture = new Fixture();
        DuplicateKeyException first = new DuplicateKeyException("fixture-first");
        CannotAcquireLockException second = new CannotAcquireLockException("fixture-second");
        DuplicateKeyException last = new DuplicateKeyException("fixture-last");
        when(fixture.users.update(isNull(), any())).thenThrow(first).thenThrow(second).thenThrow(last);

        assertSame(last, assertThrows(DuplicateKeyException.class, fixture::login));

        fixture.assertAttempts(3, 0, 3);
        assertEquals(List.of(1, 2, 3), fixture.verificationTransactions);
        assertEquals(List.of(1, 2, 3), fixture.accountReadTransactions);
        verifyNoInteractions(fixture.accounts, fixture.jwt);
    }

    @Test
    void transientFailureAlsoStopsAfterThreeAttempts() {
        Fixture fixture = new Fixture();
        CannotAcquireLockException failure = new CannotAcquireLockException("fixture-transient-limit");
        when(fixture.users.update(isNull(), any())).thenThrow(failure);

        assertSame(failure, assertThrows(CannotAcquireLockException.class, fixture::login));

        fixture.assertAttempts(3, 0, 3);
        verify(fixture.sms, times(3)).verify(PHONE, CODE);
        verifyNoInteractions(fixture.accounts, fixture.jwt);
    }

    @Test
    void validationAuthenticationIntegrityAndResourceErrorsAreNotRetried() {
        for (RuntimeException failure : List.of(
                new IllegalArgumentException("fixture-validation"),
                new InsufficientAuthenticationException("fixture-authentication"),
                new DataIntegrityViolationException("fixture-integrity"),
                new DataAccessResourceFailureException("fixture-resource"))) {
            Fixture fixture = new Fixture();
            when(fixture.users.update(isNull(), any())).thenThrow(failure);

            RuntimeException actual = assertThrows(RuntimeException.class, fixture::login);

            assertSame(failure, actual);
            fixture.assertAttempts(1, 0, 1);
            assertEquals(List.of(1), fixture.verificationTransactions);
            assertEquals(List.of(1), fixture.accountReadTransactions);
            verify(fixture.sms).verify(PHONE, CODE);
            verifyNoInteractions(fixture.accounts, fixture.jwt);
        }
    }

    @Test
    void invalidPhoneRollsBackOnceWithoutVerifyingOrReadingAnAccount() {
        Fixture fixture = new Fixture();

        assertThrows(IllegalArgumentException.class,
                () -> fixture.service.smsLogin(new AuthDtos.SmsLoginRequest("invalid", CODE, null)));

        fixture.assertAttempts(1, 0, 1);
        verifyNoInteractions(fixture.sms, fixture.users, fixture.accounts, fixture.jwt);
    }

    private static final class Fixture {
        final UserMapper users = mock(UserMapper.class);
        final UserPreferenceMapper preferences = mock(UserPreferenceMapper.class);
        final SmsService sms = mock(SmsService.class);
        final JwtService jwt = mock(JwtService.class);
        final AccountSecurityApi accounts = mock(AccountSecurityApi.class);
        final RecordingTransactions transactions = new RecordingTransactions();
        final List<Integer> verificationTransactions = new ArrayList<>();
        final List<Integer> accountReadTransactions = new ArrayList<>();
        final AuthService service;

        Fixture() {
            AppProperties properties = new AppProperties(null, new AppProperties.Jwt(null, 120, 240),
                    null, null, null, null, 1L);
            User user = new User();
            user.setId(USER_ID);
            user.setEmail(PHONE);
            user.setPhone(PHONE);
            user.setPublicUid("42000000");
            user.setNickname("SMS fixture");
            user.setStatus("active");
            user.setRole("USER");
            user.setAuthVersion(0L);
            doAnswer(call -> {
                verificationTransactions.add(transactions.activeId());
                return null;
            }).when(sms).verify(PHONE, CODE);
            when(users.selectOne(any())).thenAnswer(call -> {
                accountReadTransactions.add(transactions.activeId());
                return user;
            });
            when(users.update(isNull(), any())).thenReturn(1);
            UserPreference preference = new UserPreference();
            preference.setRemindBeforeHours(24);
            when(preferences.selectOne(any())).thenReturn(preference);
            when(accounts.createSession(any(CurrentUser.class))).thenAnswer(call -> identity(call.getArgument(0)));
            when(jwt.createToken(any(CurrentUser.class), any(Instant.class))).thenReturn("fixture-access");
            when(jwt.createRefreshToken(any(CurrentUser.class), any(Instant.class))).thenReturn("fixture-refresh");
            when(jwt.accessExpiresInSeconds()).thenReturn(7200L);
            service = new AuthService(properties, users, preferences, mock(VerificationService.class),
                    mock(PasswordEncoder.class), jwt, mock(TokenBlacklistService.class),
                    mock(PresenceService.class), sms, accounts, mock(SessionService.class), transactions);
        }

        AuthDtos.AuthResponse login() {
            return service.smsLogin(new AuthDtos.SmsLoginRequest(PHONE, CODE, null));
        }

        AccountSecurityApi.ValidatedIdentity identity(CurrentUser expected) {
            return new AccountSecurityApi.ValidatedIdentity(new CurrentUser(expected.id(), expected.email(),
                    expected.role(), expected.authVersion(), "fixture-device"), Instant.now().plusSeconds(600));
        }

        void assertAttempts(int begun, int committed, int rolledBack) {
            assertEquals(begun, transactions.attempts.size());
            assertEquals(committed, transactions.attempts.stream().filter(value -> value.committed).count());
            assertEquals(rolledBack, transactions.attempts.stream().filter(value -> value.rolledBack).count());
            assertNull(transactions.active);
            for (int index = 0; index < transactions.attempts.size(); index++) {
                Attempt attempt = transactions.attempts.get(index);
                assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, attempt.propagation);
                assertTrue(attempt.isCompleted());
                assertNotEquals(attempt.committed, attempt.rolledBack);
                if (committed == 1 && index == transactions.attempts.size() - 1) {
                    assertTrue(attempt.committed, "Only the final successful attempt may commit");
                } else {
                    assertTrue(attempt.rolledBack, "Every preceding or failed attempt must roll back");
                }
            }
        }
    }

    /** Records distinct transaction handles without a Spring context, database, or credentials. */
    private static final class RecordingTransactions implements PlatformTransactionManager {
        final List<Attempt> attempts = new ArrayList<>();
        Attempt active;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            assertNull(active, "A previous SMS attempt must finish before a fresh transaction starts");
            assertNotNull(definition);
            active = new Attempt(attempts.size() + 1, definition.getPropagationBehavior());
            attempts.add(active);
            return active;
        }

        @Override
        public void commit(TransactionStatus status) {
            Attempt attempt = complete(status);
            attempt.committed = true;
        }

        @Override
        public void rollback(TransactionStatus status) {
            Attempt attempt = complete(status);
            attempt.rolledBack = true;
        }

        int activeId() {
            assertNotNull(active, "Every complete login attempt must run inside its transaction");
            return active.id;
        }

        private Attempt complete(TransactionStatus status) {
            assertSame(active, status);
            Attempt attempt = active;
            attempt.setCompleted();
            active = null;
            return attempt;
        }
    }

    private static final class Attempt extends SimpleTransactionStatus {
        final int id;
        final int propagation;
        boolean committed;
        boolean rolledBack;

        Attempt(int id, int propagation) {
            super(true);
            this.id = id;
            this.propagation = propagation;
        }
    }
}
