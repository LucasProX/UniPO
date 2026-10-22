package com.biecuoguo.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.biecuoguo.config.AppTime;
import com.biecuoguo.domain.SmsCode;
import com.biecuoguo.mapper.SmsCodeMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SmsServiceTest {
    private final SmsCodeMapper mapper = mock(SmsCodeMapper.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);

    @BeforeAll
    static void initializeLambdaMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "sms-cas-tests"), SmsCode.class);
    }

    @Test
    void mockSendNormalizesPhoneWithoutGeneratingOrPersistingACode() {
        SmsService service = new SmsService(mapper, encoder, true);

        assertEquals("13900003333", service.send("+86 139 0000 3333"));
        verifyNoInteractions(mapper, encoder);
    }

    @Test
    void mockAcceptsDifferentFourDigitCodesWithoutDatabaseState() {
        SmsService service = new SmsService(mapper, encoder, true);

        for (String code : new String[]{"0000", "2849", "9999"}) {
            assertDoesNotThrow(() -> service.verify("13900003333", code));
        }
        verifyNoInteractions(mapper, encoder);
    }

    @Test
    void mockStillRequiresExactlyFourAsciiDigits() {
        SmsService service = new SmsService(mapper, encoder, true);

        for (String code : new String[]{null, "", "123", "12345", "12a4", "１２３４"}) {
            assertThrows(IllegalArgumentException.class, () -> service.verify("13900003333", code));
        }
        verifyNoInteractions(mapper, encoder);
    }

    @Test
    void mockStillRejectsInvalidPhoneNumbers() {
        SmsService service = new SmsService(mapper, encoder, true);

        assertThrows(IllegalArgumentException.class, () -> service.send("123"));
        assertThrows(IllegalArgumentException.class, () -> service.verify("123", "2849"));
        verifyNoInteractions(mapper, encoder);
    }

    @Test
    void realSendRemainsUnavailableUntilAProviderIsConfigured() {
        SmsService service = new SmsService(mapper, encoder, false);

        assertThrows(IllegalStateException.class, () -> service.send("13900003333"));
        verifyNoInteractions(mapper, encoder);
    }

    @Test
    void realModeDoesNotAcceptArbitraryCodeWithoutAStoredChallenge() {
        SmsService service = new SmsService(mapper, encoder, false);

        assertThrows(IllegalArgumentException.class, () -> service.verify("13900003333", "2849"));
        verify(mapper, never()).update(any(), any());
        verifyNoInteractions(encoder);
    }

    @Test
    void realModeConsumesTheMatchingUnexpiredChallenge() {
        SmsService service = new SmsService(mapper, encoder, false);
        SmsCode challenge = new SmsCode();
        challenge.setId(1L);
        challenge.setCodeHash("stored-test-hash");
        challenge.setExpiresAt(AppTime.nowLocal().plusMinutes(1));
        challenge.setConsumed(false);
        when(mapper.selectOne(any())).thenReturn(challenge);
        when(encoder.matches("2849", "stored-test-hash")).thenReturn(true);
        when(mapper.update(isNull(), any())).thenReturn(1);

        LocalDateTime before = AppTime.nowLocal();
        service.verify("13900003333", "2849");
        LocalDateTime after = AppTime.nowLocal();

        assertTrue(challenge.getConsumed());
        LambdaUpdateWrapper<SmsCode> update = capturedConsumption();
        String conditions = update.getSqlSegment();
        assertTrue(conditions.contains("id ="));
        assertTrue(conditions.contains("phone ="));
        assertTrue(conditions.contains("purpose ="));
        assertTrue(conditions.contains("consumed ="));
        assertTrue(conditions.contains("expires_at >"));
        assertTrue(update.getSqlSet().startsWith("consumed="));
        Map<String, Object> parameters = update.getParamNameValuePairs();
        assertEquals(6, parameters.size());
        for (Object expected : new Object[]{1L, "13900003333", "login", false, true}) {
            assertTrue(parameters.containsValue(expected));
        }
        LocalDateTime expiryCutoff = parameters.values().stream().filter(LocalDateTime.class::isInstance)
                .map(LocalDateTime.class::cast).findFirst().orElseThrow();
        assertFalse(expiryCutoff.isBefore(before));
        assertFalse(expiryCutoff.isAfter(after));
        verify(mapper, never()).updateById(any(SmsCode.class));
    }

    @Test
    void realModeRejectsWrongOrExpiredChallengesWithoutConsumingThem() {
        SmsService service = new SmsService(mapper, encoder, false);
        SmsCode challenge = new SmsCode();
        challenge.setCodeHash("stored-test-hash");
        challenge.setExpiresAt(AppTime.nowLocal().plusMinutes(1));
        challenge.setConsumed(false);
        when(mapper.selectOne(any())).thenReturn(challenge);

        assertThrows(IllegalArgumentException.class, () -> service.verify("13900003333", "2849"));
        challenge.setExpiresAt(AppTime.nowLocal().minusMinutes(1));
        assertThrows(IllegalArgumentException.class, () -> service.verify("13900003333", "2849"));
        verify(mapper, never()).update(any(), any());
        assertFalse(challenge.getConsumed());
    }

    @Test
    void aChallengeLostToAnotherConsumerIsRejectedWithoutMarkingTheCachedEntity() {
        SmsService service = new SmsService(mapper, encoder, false);
        SmsCode challenge = challenge(AppTime.nowLocal().plusMinutes(1));
        when(mapper.selectOne(any())).thenReturn(challenge);
        when(encoder.matches("2849", "stored-test-hash")).thenReturn(true);
        when(mapper.update(isNull(), any())).thenReturn(0);

        assertThrows(IllegalArgumentException.class, () -> service.verify("13900003333", "2849"));

        assertFalse(challenge.getConsumed());
        capturedConsumption();
        verify(mapper, never()).updateById(any(SmsCode.class));
    }

    @Test
    void aChallengeExpiringDuringPasswordComparisonFailsTheConsumptionCas() {
        SmsService service = new SmsService(mapper, encoder, false);
        LocalDateTime initiallyValid = LocalDateTime.of(2026, 10, 22, 12, 0);
        LocalDateTime afterComparison = initiallyValid.plusSeconds(2);
        SmsCode challenge = challenge(initiallyValid.plusSeconds(1));
        when(mapper.selectOne(any())).thenReturn(challenge);
        when(encoder.matches("2849", "stored-test-hash")).thenReturn(true);
        when(mapper.update(isNull(), any())).thenReturn(0);

        try (MockedStatic<AppTime> clock = mockStatic(AppTime.class)) {
            clock.when(AppTime::nowLocal).thenReturn(initiallyValid, afterComparison);
            assertThrows(IllegalArgumentException.class, () -> service.verify("13900003333", "2849"));
        }

        assertFalse(challenge.getConsumed());
        LambdaUpdateWrapper<SmsCode> update = capturedConsumption();
        assertTrue(update.getSqlSegment().contains("expires_at >"));
        assertTrue(update.getParamNameValuePairs().containsValue(afterComparison));
        assertFalse(challenge.getExpiresAt().isAfter(afterComparison));
        verify(mapper, never()).updateById(any(SmsCode.class));
    }

    private static SmsCode challenge(LocalDateTime expiresAt) {
        SmsCode challenge = new SmsCode();
        challenge.setId(1L);
        challenge.setPhone("13900003333");
        challenge.setPurpose("login");
        challenge.setCodeHash("stored-test-hash");
        challenge.setExpiresAt(expiresAt);
        challenge.setConsumed(false);
        return challenge;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private LambdaUpdateWrapper<SmsCode> capturedConsumption() {
        ArgumentCaptor<LambdaUpdateWrapper<SmsCode>> update = (ArgumentCaptor) ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), update.capture());
        return update.getValue();
    }
}
