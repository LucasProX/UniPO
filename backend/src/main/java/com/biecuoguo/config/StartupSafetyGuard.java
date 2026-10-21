package com.biecuoguo.config;

import com.biecuoguo.service.VerificationMailer;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 启动期安全检查：危险的「开发开关」在非开发环境下会把服务直接按死，而不是只打条警告。
 *
 * <p>为什么要在启动时炸，而不是运行时返回错误：这些开关一旦漏到生产，漏洞是静默的——
 * 不会报错、不会有异常日志、监控面板一片绿，只有等你发现某个陌生邮箱注册进来了才知道。
 * 打一条 WARN 挡不住任何人（没人看启动日志），所以选择让进程起不来。
 *
 * <p>同类静默故障还有一种反向的：验证码配成真实模式却没配 SMTP。这时候进程起得来、
 * 接口也返回 200，但验证码永远发不出去，新用户注册不了 —— 同样没人会发现，所以一并拦。
 *
 * <p>为什么用 {@code @PostConstruct} 而不是 {@code ApplicationRunner}：
 * ApplicationRunner 在容器初始化完之后才跑，那时候 {@code AuthService} 已经把演示账号
 * 和重置过的管理员密码写进库了。要在写库之前拦住，就必须在 bean 初始化阶段完成。
 */
@Component
public class StartupSafetyGuard {

    private static final Logger log = LoggerFactory.getLogger(StartupSafetyGuard.class);

    /** 允许开危险开关的 profile。加白名单要慎重，这里每加一个都是新的漏点。 */
    private static final List<String> DEV_PROFILES = List.of("local", "dev", "test");

    /**
     * 曾经写死在 application.yml / .env.example / docker-compose.yml 里的 JWT 示例值。
     * 它们已经公开，谁都能拿来签一个合法 token——所以出现在配置里就要拒绝启动。
     * 只用于比较，任何日志和异常信息都不要把它们打印出来。
     */
    private static final List<String> KNOWN_SECRETS = List.of(
            "change-this-secret-to-a-very-long-random-string-32-plus",
            "change_me_to_a_long_random_secret_at_least_32_chars",
            "please-change-this-to-a-very-long-random-secret-32"
    );

    /** application-local.yml 里的本地专用密钥：开发能用，非开发环境用它会拒绝启动。 */
    private static final String DEV_SECRET = "local-dev-only-jwt-secret-never-deploy-this-01";

    /** HS256 要求密钥至少 256 位，短于 32 字节 JwtService 直接抛异常。 */
    private static final int MIN_SECRET_LENGTH = 32;
    /** 首次管理员凭据不使用公开默认值；部署配置应来自随机生成器。 */
    private static final int MIN_BOOTSTRAP_ADMIN_PASSWORD_LENGTH = 32;

    private final AppProperties properties;
    private final Environment environment;
    private final VerificationMailer mailer;

    public StartupSafetyGuard(AppProperties properties, Environment environment, VerificationMailer mailer) {
        this.properties = properties;
        this.environment = environment;
        this.mailer = mailer;
    }

    @PostConstruct
    void check() {
        List<String> active = Arrays.asList(environment.getActiveProfiles());
        boolean devLike = isDevOnlyProfiles(active);

        // 0) JWT 签名密钥不能是空的、示例值太短的。
        //    这一条不区分 profile，也不打 WARN 放行：空密钥根本签不出 token，
        //    而公开的密钥等于把「伪造一个 ADMIN 身份」这件事变成公开知识。
        //    本地开发由 application-local.yml 提供专用值，所以这里可以一律要求。
        String secret = properties.jwt() == null ? null : properties.jwt().secret();
        String secretProblem = jwtSecretProblem(secret, devLike);
        if (secretProblem != null) {
            throw new IllegalStateException("拒绝启动：" + secretProblem + "\n"
                    + "这个值是 JWT 的签名密钥，公开或者为空都等于把管理员权限送人："
                    + "任何人都能自己签一个 role=ADMIN 的 token。\n"
                    + "生成一个随机的：openssl rand -base64 48，然后设进 JWT_SECRET。\n"
                    + "本地开发请用 --spring.profiles.active=local（application-local.yml 里有本地专用值）。");
        }

        // 首次初始化管理员时会将该值写入 password_hash。通用配置不能带公开弱密码默认值，
        // 否则全新数据库在 Compose / 直接启动路径上都会创建可预测的管理员账号。
        String adminPassword = properties.bootstrap() == null
                ? null : properties.bootstrap().adminPassword();
        String adminPasswordProblem = bootstrapAdminPasswordProblem(adminPassword, devLike);
        if (adminPasswordProblem != null) {
            throw new IllegalStateException("拒绝启动：" + adminPasswordProblem + "\n"
                    + "首次初始化管理员会使用该密码。部署环境请设置 BOOTSTRAP_ADMIN_PASSWORD，"
                    + "并用 openssl rand -hex 32 生成随机值；本地开发请使用 local profile。");
        }

        boolean mockVerification = properties.verification() != null
                && properties.verification().mockAcceptAnyCode();
        boolean mockSms = Boolean.TRUE.equals(environment.getProperty(
                "app.sms.mock-accept-any-code", Boolean.class, false));
        boolean seedDemo = properties.bootstrap() != null && properties.bootstrap().seedDemoData();

        // 1) 真实模式下必须有可用的 SMTP。这条不区分 profile：只要验证码是真实模式，
        //    没有 SMTP 就一定发不出码，注册和找回密码整条链路是断的。
        if (!mockVerification && !mailer.available()) {
            throw new IllegalStateException("拒绝启动：验证码处于真实模式"
                    + "（app.verification.mock-accept-any-code=false），但没有配置 SMTP。\n"
                    + "这样注册和找回密码都发不出验证码，新用户永远注册不了。\n"
                    + "请配置 SMTP_HOST / SMTP_PORT / SMTP_USERNAME / SMTP_PASSWORD（见 .env.example）；\n"
                    + "本地开发请加 --spring.profiles.active=local 走 mock 模式（任意 4 位数字放行）。");
        }

        // 2) 危险开关在开发环境放行，只打警告。
        if (devLike) {
            if (mockVerification) {
                log.warn("⚠ 验证码处于 mock 模式：任意 4 位数字都能通过校验，任何人可以注册任意邮箱。"
                        + "当前 profile={}，只允许本地使用。", active);
            }
            if (mockSms) {
                log.warn("⚠ 短信验证码处于测试模式：不发送短信，任意 4 位数字可用于测试登录。"
                        + "当前 profile={}，仅允许开发/测试环境。", active);
            }
            if (seedDemo) {
                log.warn("⚠ 演示数据写入已开启：启动时会创建 good / xiaoba / yuanhua 三个弱口令账号。"
                        + "当前 profile={}，只允许本地使用。", active);
            }
            return;
        }

        // 3) 非开发环境下一律拒绝。
        if (mockVerification || seedDemo || mockSms) {
            String detail = (mockVerification ? "app.verification.mock-accept-any-code=true" : "")
                    + (mockVerification && mockSms ? "，" : "")
                    + (mockSms ? "app.sms.mock-accept-any-code=true" : "")
                    + ((mockVerification || mockSms) && seedDemo ? "，" : "")
                    + (seedDemo ? "app.bootstrap.seed-demo-data=true" : "");
            throw new IllegalStateException("拒绝启动：当前 profile=" + active
                    + " 不是开发环境，但开启了危险开关 [" + detail + "]。"
                    + "这两个开关会让任何人都能注册任意邮箱、并往库里写弱口令演示账号。"
                    + "确认是本地开发请加 --spring.profiles.active=local；"
                    + "如果是生产环境，请把上述配置改回 false。");
        }
    }
    /**
     * 检查 JWT 密钥，返回一句「哪里不对」，没问题返回 {@code null}。
     *
     * <p>注意：返回的文案里**不要带上密钥本身**，异常信息会进日志。
     */
    private String jwtSecretProblem(String secret, boolean devLike) {
        if (secret == null || secret.isBlank()) {
            return "app.jwt.secret（JWT_SECRET）没有配置。";
        }
        if (KNOWN_SECRETS.contains(secret)) {
            return "app.jwt.secret 还是文档里的示例值。";
        }
        if (secret.length() < MIN_SECRET_LENGTH) {
            return "app.jwt.secret 太短（至少 " + MIN_SECRET_LENGTH + " 个字符，HS256 的密钥下限）。";
        }
        if (!devLike && DEV_SECRET.equals(secret)) {
            return "app.jwt.secret 用的是 application-local.yml 里的本地开发专用值。";
        }
        return null;
    }

    /** 部署 profile 需要随机高强度凭据，本地 profile 允许保留演示账号的便利性。 */
    static String bootstrapAdminPasswordProblem(String password, boolean devLike) {
        if (password == null || password.isBlank()) {
            return "BOOTSTRAP_ADMIN_PASSWORD 未配置。";
        }
        if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            return "BOOTSTRAP_ADMIN_PASSWORD 超过 BCrypt 的 72 字节上限。";
        }
        if (devLike) {
            return null;
        }
        if (password.length() < MIN_BOOTSTRAP_ADMIN_PASSWORD_LENGTH) {
            return "BOOTSTRAP_ADMIN_PASSWORD 过短（至少 "
                    + MIN_BOOTSTRAP_ADMIN_PASSWORD_LENGTH + " 个字符）。";
        }
        String normalized = password.toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("change_me") || normalized.startsWith("change-this")
                || normalized.startsWith("please-change") || normalized.startsWith("local-dev-only")
                || normalized.contains("your-password") || normalized.contains("your_password")) {
            return "BOOTSTRAP_ADMIN_PASSWORD 仍是公开占位值。";
        }
        return null;
    }

    static boolean isDevOnlyProfiles(List<String> activeProfiles) {
        return activeProfiles != null && !activeProfiles.isEmpty()
                && activeProfiles.stream().allMatch(DEV_PROFILES::contains);
    }
}
