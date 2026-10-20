package com.biecuoguo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * 验证码邮件投递，只在“真实模式”下被调用。
 *
 * <p>开发模式（{@code app.verification.mock-accept-any-code=true}）下 {@link VerificationService}
 * 直接跳过发信，根本走不到这里 —— 那条路径不写库、不发邮件、任意 4 位数字都算通过。
 *
 * <p>能不能真的发出去取决于 {@code spring.mail.host}（环境变量 {@code SMTP_HOST}）。
 * Spring Boot 只在 host 非空时才创建 {@link JavaMailSender}，所以这里用 ObjectProvider 取；
 * 取不到就说明没配 —— 由 {@link #available()} 暴露给 {@code StartupSafetyGuard}，让服务直接起不来。
 * 否则要等用户点了「发送验证码」才发现注册链路是断的。
 */
@Component
public class VerificationMailer {

    private static final Logger log = LoggerFactory.getLogger(VerificationMailer.class);

    private final ObjectProvider<JavaMailSender> mailSenders;
    private final String host;
    private final String from;

    public VerificationMailer(ObjectProvider<JavaMailSender> mailSenders,
                              @Value("${spring.mail.host:}") String host,
                              @Value("${spring.mail.username:}") String from) {
        this.mailSenders = mailSenders;
        this.host = host;
        this.from = from;
    }

    /** SMTP 是否可用。注意要连 host 一起判：host 配成空串时 Spring 仍会创建 bean。 */
    public boolean available() {
        return host != null && !host.isBlank() && mailSenders.getIfAvailable() != null;
    }

    public void sendCode(String email, String purpose, String code, int ttlMinutes) {
        JavaMailSender sender = mailSenders.getIfAvailable();
        if (sender == null || host == null || host.isBlank()) {
            throw new IllegalStateException("未配置 SMTP（SMTP_HOST），无法发送验证码");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        if (from != null && !from.isBlank()) {
            message.setFrom(from);
        }
        message.setTo(email);
        message.setSubject("【校园 PO】验证码 " + code);
        message.setText("你的验证码是：" + code + "\n\n"
                + ttlMinutes + " 分钟内有效，用于" + purposeText(purpose) + "。\n"
                + "如果不是你本人操作，忽略这封邮件即可。");
        sender.send(message);
        log.info("[verification] 验证码邮件已发送 email={} purpose={}", email, purpose);
    }

    private static String purposeText(String purpose) {
        return switch (purpose) {
            case "register" -> "注册账号";
            case "reset-password" -> "重置密码";
            default -> purpose;
        };
    }
}