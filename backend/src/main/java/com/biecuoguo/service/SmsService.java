package com.biecuoguo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.biecuoguo.domain.SmsCode;
import com.biecuoguo.mapper.SmsCodeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 短信验证码。
 *
 * <p><b>当前只接了本地 mock</b>：真实短信通道（阿里云/腾讯云）还没接，非 local 环境调用
 * {@link #send(String)} 会直接报「短信服务未配置」，不会静默放行。
 * 上线前必须接真实 provider，并把 {@code app.sms.mock-accept-any-code} 关掉。
 */
@Service
public class SmsService {

    private static final Logger log = LoggerFactory.getLogger(SmsService.class);
    private static final String MOCK_CODE = "1234";
    private static final int EXPIRE_MINUTES = 5;

    private final SmsCodeMapper smsCodeMapper;
    private final PasswordEncoder passwordEncoder;
    private final boolean mock;

    public SmsService(SmsCodeMapper smsCodeMapper, PasswordEncoder passwordEncoder,
                      @Value("${app.sms.mock-accept-any-code:false}") boolean mock) {
        this.smsCodeMapper = smsCodeMapper;
        this.passwordEncoder = passwordEncoder;
        this.mock = mock;
    }

    @Transactional
    public String send(String rawPhone) {
        String phone = normalize(rawPhone);
        if (!mock) {
            throw new IllegalStateException("短信服务未配置，请联系管理员");
        }
        String code = MOCK_CODE;
        LocalDateTime now = LocalDateTime.now();
        smsCodeMapper.delete(new LambdaQueryWrapper<SmsCode>()
                .eq(SmsCode::getPhone, phone)
                .eq(SmsCode::getPurpose, "login")
                .eq(SmsCode::getConsumed, false));
        SmsCode entity = new SmsCode();
        entity.setPhone(phone);
        entity.setPurpose("login");
        entity.setCodeHash(passwordEncoder.encode(code));
        entity.setExpiresAt(now.plusMinutes(EXPIRE_MINUTES));
        entity.setConsumed(false);
        entity.setCreatedAt(now);
        smsCodeMapper.insert(entity);
        log.info("短信 mock 模式：phone={} code={}（仅本地，不会真的发送；任意 4 位数字都通过）", phone, code);
        return phone;
    }

    @Transactional
    public void verify(String rawPhone, String code) {
        String phone = normalize(rawPhone);
        if (code == null || !code.matches("\\d{4}")) {
            throw new IllegalArgumentException("验证码格式不正确");
        }
        // 本地 mock 模式：任意 4 位数字都通过，方便联调；真实模式走下面的哈希校验。
        if (mock) {
            return;
        }
        SmsCode entity = smsCodeMapper.selectOne(new LambdaQueryWrapper<SmsCode>()
                .eq(SmsCode::getPhone, phone)
                .eq(SmsCode::getPurpose, "login")
                .eq(SmsCode::getConsumed, false)
                .orderByDesc(SmsCode::getId)
                .last("LIMIT 1"));
        if (entity == null || entity.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("验证码已过期，请重新获取");
        }
        if (!passwordEncoder.matches(code, entity.getCodeHash())) {
            throw new IllegalArgumentException("验证码不正确");
        }
        entity.setConsumed(true);
        smsCodeMapper.updateById(entity);
    }

    public static String normalize(String raw) {
        String phone = raw == null ? "" : raw.replaceAll("\\s+", "");
        if (phone.startsWith("+86")) phone = phone.substring(3);
        if (phone.startsWith("86") && phone.length() > 11) phone = phone.substring(2);
        if (!phone.matches("1[3-9]\\d{9}")) {
            throw new IllegalArgumentException("请输入正确的手机号");
        }
        return phone;
    }

    public boolean mockMode() {
        return mock;
    }

    public static int expireMinutes() {
        return EXPIRE_MINUTES;
    }
}
