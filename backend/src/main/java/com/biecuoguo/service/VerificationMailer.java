package com.biecuoguo.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

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
 *
 * <p>邮件是 multipart/alternative：纯文本兜底 + HTML 卡片。只发 HTML 的话，纯文本客户端
 * （部分企业邮箱、终端 mutt、手表通知）会看到空白或一堆标签；只发纯文本又显得太敷衍，
 * 所以两份都带。HTML 里不用外链图片 —— 邮件客户端默认拦截远程图片，只剩一堆破图占位符。
 */
@Component
public class VerificationMailer {

    private static final Logger log = LoggerFactory.getLogger(VerificationMailer.class);

    /** 和前端主色保持一致，改品牌色时两处一起改。 */
    private static final String BRAND = "#1d4ed8";
    private static final String BRAND_SOFT = "#eef2ff";
    private static final String INK = "#0f172a";
    private static final String MUTED = "#64748b";
    private static final String LINE = "#e2e8f0";
    private static final String PAGE_BG = "#eef1f6";
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ObjectProvider<JavaMailSender> mailSenders;
    private final String host;
    private final String from;
    private final ZoneId zone;

    public VerificationMailer(ObjectProvider<JavaMailSender> mailSenders,
                              @Value("${spring.mail.host:}") String host,
                              @Value("${spring.mail.username:}") String from,
                              @Value("${app.time-zone:Asia/Shanghai}") String timeZone) {
        this.mailSenders = mailSenders;
        this.host = host;
        this.from = from;
        this.zone = ZoneId.of(timeZone);
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
        String purposeText = purposeText(purpose);
        String requestedAt = ZonedDateTime.now(zone).format(TIME_FMT);
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            if (from != null && !from.isBlank()) {
                helper.setFrom(from);
            }
            helper.setTo(email);
            helper.setSubject("【UniPO】验证码 " + code);
            helper.setText(
                    textBody(code, purposeText, ttlMinutes, requestedAt),
                    htmlBody(code, purposeText, ttlMinutes, requestedAt));
            sender.send(message);
        } catch (MessagingException e) {
            throw new IllegalStateException("验证码邮件构造失败", e);
        }
        log.info("[verification] 验证码邮件已发送 email={} purpose={}", email, purpose);
    }

    /** 纯文本兜底：不依赖任何客户端渲染能力。 */
    static String textBody(String code, String purposeText, int ttlMinutes, String requestedAt) {
        return "你的 UniPO 验证码是：" + code + "\n\n"
                + "用途：" + purposeText + "\n"
                + "有效期：" + ttlMinutes + " 分钟（只能使用一次）\n"
                + "请求时间：" + requestedAt + "\n\n"
                + "如果不是你本人操作，忽略这封邮件即可。\n\n"
                + "UniPO";
    }

    /**
     * HTML 卡片。全部内联样式 + 表格布局（Outlook 对 flex/grid 支持很差），
     * 验证码单独放一个色块，方便在手机上直接复制。
     */
    static String htmlBody(String code, String purposeText, int ttlMinutes, String requestedAt) {
        return "<!doctype html>"
                + "<html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta name=\"color-scheme\" content=\"light only\">"
                + "<title>UniPO 验证码</title></head>"
                + "<body style=\"margin:0;padding:0;background:" + PAGE_BG + ";\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"background:" + PAGE_BG + ";padding:40px 16px;\"><tr><td align=\"center\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"max-width:560px;background:#ffffff;border:1px solid " + LINE + ";"
                + "border-radius:18px;overflow:hidden;\">"

                // 顶部品牌条
                + "<tr><td style=\"background:" + BRAND + ";padding:22px 30px;"
                + "background-image:linear-gradient(135deg,#1d4ed8 0%,#3b82f6 100%);\">"
                + "<div style=\"font:700 20px/1.2 -apple-system,BlinkMacSystemFont,'Segoe UI',"
                + "Roboto,'PingFang SC','Microsoft YaHei',sans-serif;color:#ffffff;"
                + "letter-spacing:2px;\">UniPO</div>"
                + "<div style=\"margin-top:4px;font:400 11px/1.4 -apple-system,BlinkMacSystemFont,"
                + "'Segoe UI',Roboto,'PingFang SC','Microsoft YaHei',sans-serif;color:#dbeafe;"
                + "letter-spacing:3px;text-transform:uppercase;\">Account Security</div>"
                + "</td></tr>"

                // 标题与说明
                + "<tr><td style=\"padding:32px 30px 0;\">"
                + "<div style=\"font:700 22px/1.35 -apple-system,BlinkMacSystemFont,'Segoe UI',"
                + "Roboto,'PingFang SC','Microsoft YaHei',sans-serif;color:" + INK + ";\">"
                + "验证你的邮箱</div>"
                + "<div style=\"margin-top:10px;font:400 14px/1.75 -apple-system,BlinkMacSystemFont,"
                + "'Segoe UI',Roboto,'PingFang SC','Microsoft YaHei',sans-serif;color:" + MUTED + ";\">"
                + "你正在<strong style=\"color:" + INK + ";\">" + purposeText + "</strong>，"
                + "请在页面中输入下面的验证码。"
                + "</div></td></tr>"

                // 验证码色块
                + "<tr><td style=\"padding:24px 30px 0;\">"
                + "<div style=\"background:" + BRAND_SOFT + ";border:1px solid #dbe4ff;"
                + "border-radius:14px;padding:20px 16px 18px;text-align:center;\">"
                + "<div style=\"font:600 10px/1.4 -apple-system,BlinkMacSystemFont,'Segoe UI',"
                + "Roboto,'PingFang SC','Microsoft YaHei',sans-serif;color:" + MUTED + ";"
                + "letter-spacing:2px;\">VERIFICATION CODE</div>"
                + "<div style=\"margin-top:10px;font:700 38px/1.15 ui-monospace,SFMono-Regular,Menlo,"
                + "Consolas,monospace;color:" + BRAND + ";letter-spacing:12px;padding-left:12px;\">"
                + code + "</div>"
                + "<div style=\"margin-top:10px;font:400 12px/1.4 -apple-system,BlinkMacSystemFont,"
                + "'Segoe UI',Roboto,'PingFang SC','Microsoft YaHei',sans-serif;color:" + MUTED + ";\">"
                + "长按或双击可直接复制</div>"
                + "</div></td></tr>"

                // 明细
                + "<tr><td style=\"padding:22px 30px 0;\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"border-top:1px solid " + LINE + ";\">"
                + metaRow("用途", purposeText)
                + metaRow("有效期", ttlMinutes + " 分钟 · 仅可使用一次")
                + metaRow("请求时间", requestedAt + " (GMT+8)")
                + "</table></td></tr>"

                // 安全提示
                + "<tr><td style=\"padding:22px 30px 30px;\">"
                + "<div style=\"background:#fff7ed;border:1px solid #fed7aa;border-radius:12px;"
                + "padding:14px 16px;\">"
                + "<div style=\"font:600 13px/1.5 -apple-system,BlinkMacSystemFont,'Segoe UI',"
                + "Roboto,'PingFang SC','Microsoft YaHei',sans-serif;color:#9a3412;\">"
                + "不是你本人操作？</div>"
                + "<div style=\"margin-top:4px;font:400 12px/1.7 -apple-system,BlinkMacSystemFont,"
                + "'Segoe UI',Roboto,'PingFang SC','Microsoft YaHei',sans-serif;color:#9a3412;\">"
                + "忽略并删除这封邮件即可，你的账号不会被创建或修改。建议同时检查账号安全设置。"
                + "</div></div></td></tr>"

                // 卡片页脚
                + "<tr><td style=\"background:#f8fafc;border-top:1px solid " + LINE + ";"
                + "padding:16px 30px;\">"
                + "<div style=\"font:400 11px/1.6 -apple-system,BlinkMacSystemFont,'Segoe UI',"
                + "Roboto,'PingFang SC','Microsoft YaHei',sans-serif;color:#94a3b8;\">"
                + "此邮件由系统自动发送，请勿直接回复。"
                + "</div></td></tr>"

                + "</table></td></tr></table></body></html>";
    }

    private static String metaRow(String label, String value) {
        return "<tr>"
                + "<td style=\"padding:12px 0 0;width:88px;vertical-align:top;font:400 12px/1.6 "
                + "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'PingFang SC','Microsoft YaHei',"
                + "sans-serif;color:#94a3b8;\">" + label + "</td>"
                + "<td style=\"padding:12px 0 0;font:500 13px/1.6 -apple-system,BlinkMacSystemFont,"
                + "'Segoe UI',Roboto,'PingFang SC','Microsoft YaHei',sans-serif;color:" + INK + ";\">"
                + value + "</td></tr>";
    }

    private static String purposeText(String purpose) {
        return switch (purpose) {
            case "register" -> "注册账号";
            case "reset-password" -> "重置密码";
            default -> purpose;
        };
    }
}
