package com.biecuoguo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.biecuoguo.config.AppProperties;
import com.biecuoguo.domain.VerificationCode;
import com.biecuoguo.mapper.VerificationCodeMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Locale;

@Service
public class VerificationService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final VerificationCodeMapper verificationCodeMapper;
    private final AppProperties properties;
    private final PasswordEncoder passwordEncoder;

    public VerificationService(VerificationCodeMapper verificationCodeMapper, AppProperties properties, PasswordEncoder passwordEncoder) {
        this.verificationCodeMapper = verificationCodeMapper;
        this.properties = properties;
        this.passwordEncoder = passwordEncoder;
    }

    public void requestCode(String email, String purpose) {
        String normalizedEmail = normalize(email);
        String normalizedPurpose = normalizePurpose(purpose);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusMinutes(10);
        String code = generateCode();
        boolean mockAcceptAnyCode = properties.verification() == null || properties.verification().mockAcceptAnyCode();

        verificationCodeMapper.delete(new LambdaQueryWrapper<VerificationCode>()
                .eq(VerificationCode::getEmail, normalizedEmail)
                .eq(VerificationCode::getPurpose, normalizedPurpose)
                .isNull(VerificationCode::getConsumedAt));

        VerificationCode verificationCode = new VerificationCode();
        verificationCode.setEmail(normalizedEmail);
        verificationCode.setPurpose(normalizedPurpose);
        verificationCode.setCodeHash(passwordEncoder.encode(code));
        verificationCode.setExpiresAt(expiresAt);
        verificationCode.setCreatedAt(now);
        verificationCode.setUpdatedAt(now);
        verificationCodeMapper.insert(verificationCode);

        if (!mockAcceptAnyCode) {
            // Placeholder for real email delivery integration.
            // When SMTP is wired, send `code` to `normalizedEmail` here.
            System.out.printf(Locale.ROOT, "[verification] send %s to %s for %s%n", code, normalizedEmail, normalizedPurpose);
        }
    }

    public void verifyCode(String email, String purpose, String code) {
        String normalizedEmail = normalize(email);
        String normalizedPurpose = normalizePurpose(purpose);
        String normalizedCode = normalizeCode(code);
        VerificationCode verificationCode = verificationCodeMapper.selectOne(new LambdaQueryWrapper<VerificationCode>()
                .eq(VerificationCode::getEmail, normalizedEmail)
                .eq(VerificationCode::getPurpose, normalizedPurpose)
                .isNull(VerificationCode::getConsumedAt)
                .orderByDesc(VerificationCode::getCreatedAt)
                .last("limit 1"));
        if (verificationCode == null) {
            throw new IllegalArgumentException("验证码不存在或已失效");
        }
        if (verificationCode.getExpiresAt() != null && verificationCode.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("验证码已过期");
        }
        boolean mockAcceptAnyCode = properties.verification() == null || properties.verification().mockAcceptAnyCode();
        if (!mockAcceptAnyCode && !passwordEncoder.matches(normalizedCode, verificationCode.getCodeHash())) {
            throw new IllegalArgumentException("验证码不正确");
        }
        if (mockAcceptAnyCode) {
            if (!normalizedCode.matches("\\d{4}")) {
                throw new IllegalArgumentException("验证码不正确");
            }
        }
        verificationCode.setConsumedAt(LocalDateTime.now());
        verificationCode.setUpdatedAt(LocalDateTime.now());
        verificationCodeMapper.updateById(verificationCode);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizePurpose(String purpose) {
        String normalized = normalize(purpose);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("验证码用途不能为空");
        }
        return normalized;
    }

    private static String normalizeCode(String code) {
        String normalized = code == null ? "" : code.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("请输入验证码");
        }
        return normalized;
    }

    private static String generateCode() {
        return String.format(Locale.ROOT, "%04d", RANDOM.nextInt(10000));
    }
}
