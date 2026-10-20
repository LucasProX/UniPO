package com.biecuoguo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.biecuoguo.config.AppProperties;
import com.biecuoguo.domain.VerificationCode;
import com.biecuoguo.mapper.VerificationCodeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 邮箱验证码。两种模式，由 {@code app.verification.mock-accept-any-code}（{@code VERIFICATION_MOCK_ACCEPT_ANY_CODE}）切换：
 *
 * <ul>
 *   <li><b>开发模式</b>（true）：不写库、不发邮件，任意 4 位数字都算通过。方便随手建测试号。</li>
 *   <li><b>真实模式</b>（false）：生成随机码 -> 存 BCrypt 哈希 -> 通过 SMTP 发信 -> 校验并消费。</li>
 * </ul>
 *
 * <p><b>区分“开关”和“跳过”很重要</b>：注册/重置密码这两个流程里验证码字段**始终是必填的**，
 * 开关只决定后端拿它怎么办 —— 这样开发阶段的调用链和线上完全一致，不会出现“本地能过、上线才发现漏了校验”。
 *
 * <p>没写配置时按真实模式处理：宁可发不出邮件报错，也不要静默放过所有人。
 */
@Service
public class VerificationService {

    private static final Logger log = LoggerFactory.getLogger(VerificationService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int CODE_TTL_MINUTES = 10;
    private static final String CODE_PATTERN = "\\d{4}";

    private final VerificationCodeMapper verificationCodeMapper;
    private final AppProperties properties;
    private final PasswordEncoder passwordEncoder;
    private final VerificationMailer mailer;

    public VerificationService(VerificationCodeMapper verificationCodeMapper, AppProperties properties,
                               PasswordEncoder passwordEncoder, VerificationMailer mailer) {
        this.verificationCodeMapper = verificationCodeMapper;
        this.properties = properties;
        this.passwordEncoder = passwordEncoder;
        this.mailer = mailer;
    }

    /** true = 开发模式（放行任意 4 位数字）；false = 真实模式（真发信、真比对）。 */
    public boolean mockMode() {
        return properties.verification() != null && properties.verification().mockAcceptAnyCode();
    }

    /**
     * 请求发送验证码。
     *
     * <p>开发模式下**什么都不做**（不落库、不发信）——直接 return。调用方仍然拿到 200，
     * 前端照常进入倒计时，用户体验和真实发信一致。
     */
    public void requestCode(String email, String purpose) {
        String normalizedEmail = normalize(email);
        String normalizedPurpose = normalizePurpose(purpose);
        if (mockMode()) {
            log.warn("[verification] 开发模式（mock-accept-any-code=true）：不发送邮件，{} 时任意 4 位数字都算通过。email={}",
                    normalizedPurpose, normalizedEmail);
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        String code = generateCode();

        // 同一邮箱 + 同一用途只保留最新一条未消费的，避免旧码还能用。
        verificationCodeMapper.delete(new LambdaQueryWrapper<VerificationCode>()
                .eq(VerificationCode::getEmail, normalizedEmail)
                .eq(VerificationCode::getPurpose, normalizedPurpose)
                .isNull(VerificationCode::getConsumedAt));

        VerificationCode verificationCode = new VerificationCode();
        verificationCode.setEmail(normalizedEmail);
        verificationCode.setPurpose(normalizedPurpose);
        verificationCode.setCodeHash(passwordEncoder.encode(code));
        verificationCode.setExpiresAt(now.plusMinutes(CODE_TTL_MINUTES));
        verificationCode.setCreatedAt(now);
        verificationCode.setUpdatedAt(now);
        verificationCodeMapper.insert(verificationCode);

        mailer.sendCode(normalizedEmail, normalizedPurpose, code, CODE_TTL_MINUTES);
    }

    /**
     * 校验验证码。两种模式下**都必须调用**，只是实现不同。
     *
     * <p>空值一律在 {@link #normalizeCode(String)} 就被拒掉，所以“不填验证码”在两种模式下都过不去。
     */
    public void verifyCode(String email, String purpose, String code) {
        String normalizedCode = normalizeCode(code);

        if (mockMode()) {
            if (!normalizedCode.matches(CODE_PATTERN)) {
                throw new IllegalArgumentException("验证码需为 4 位数字（开发模式随便填 4 位数字即可）");
            }
            return;
        }

        String normalizedEmail = normalize(email);
        String normalizedPurpose = normalizePurpose(purpose);
        VerificationCode verificationCode = verificationCodeMapper.selectOne(new LambdaQueryWrapper<VerificationCode>()
                .eq(VerificationCode::getEmail, normalizedEmail)
                .eq(VerificationCode::getPurpose, normalizedPurpose)
                .isNull(VerificationCode::getConsumedAt)
                .orderByDesc(VerificationCode::getCreatedAt)
                .last("limit 1"));
        if (verificationCode == null) {
            throw new IllegalArgumentException("验证码不存在或已失效，请重新获取");
        }
        if (verificationCode.getExpiresAt() != null && verificationCode.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("验证码已过期，请重新获取");
        }
        if (!passwordEncoder.matches(normalizedCode, verificationCode.getCodeHash())) {
            throw new IllegalArgumentException("验证码不正确");
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