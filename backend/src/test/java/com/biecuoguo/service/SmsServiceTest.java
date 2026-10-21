package com.biecuoguo.service;

import com.biecuoguo.domain.SmsCode;
import com.biecuoguo.mapper.SmsCodeMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SmsServiceTest {
    private final SmsCodeMapper mapper = mock(SmsCodeMapper.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);

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
        verify(mapper, never()).updateById(any(SmsCode.class));
        verifyNoInteractions(encoder);
    }

    @Test
    void realModeConsumesTheMatchingUnexpiredChallenge() {
        SmsService service = new SmsService(mapper, encoder, false);
        SmsCode challenge = new SmsCode();
        challenge.setId(1L);
        challenge.setCodeHash("stored-test-hash");
        challenge.setExpiresAt(LocalDateTime.now().plusMinutes(1));
        challenge.setConsumed(false);
        when(mapper.selectOne(any())).thenReturn(challenge);
        when(encoder.matches("2849", "stored-test-hash")).thenReturn(true);

        service.verify("13900003333", "2849");

        assertTrue(challenge.getConsumed());
        verify(mapper).updateById(challenge);
    }

    @Test
    void realModeRejectsWrongOrExpiredChallengesWithoutConsumingThem() {
        SmsService service = new SmsService(mapper, encoder, false);
        SmsCode challenge = new SmsCode();
        challenge.setCodeHash("stored-test-hash");
        challenge.setExpiresAt(LocalDateTime.now().plusMinutes(1));
        challenge.setConsumed(false);
        when(mapper.selectOne(any())).thenReturn(challenge);

        assertThrows(IllegalArgumentException.class, () -> service.verify("13900003333", "2849"));
        challenge.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        assertThrows(IllegalArgumentException.class, () -> service.verify("13900003333", "2849"));
        verify(mapper, never()).updateById(any(SmsCode.class));
    }
}
