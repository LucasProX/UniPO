package com.biecuoguo.config;

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
 * <p>为什么用 {@code @PostConstruct} 而不是 {@code ApplicationRunner}：
 * ApplicationRunner 在容器初始化完之后才跑，那时候 {@code AuthService} 已经把演示账号
 * 和重置过的管理员密码写进库了。要在写库之前拦住，就必须在 bean 初始化阶段完成。
 */
@Component
public class StartupSafetyGuard {

    private static final Logger log = LoggerFactory.getLogger(StartupSafetyGuard.class);

    /** 允许开危险开关的 profile。加白名单要慎重，这里每加一个都是新的漏点。 */
    private static final List<String> DEV_PROFILES = List.of("local", "dev", "test");

    private final AppProperties properties;
    private final Environment environment;

    public StartupSafetyGuard(AppProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @PostConstruct
    void check() {
        List<String> active = Arrays.asList(environment.getActiveProfiles());
        boolean devLike = active.stream().anyMatch(DEV_PROFILES::contains);

        boolean mockVerification = properties.verification() != null
                && properties.verification().mockAcceptAnyCode();
        boolean seedDemo = properties.bootstrap() != null && properties.bootstrap().seedDemoData();

        if (devLike) {
            if (mockVerification) {
                log.warn("⚠ 验证码处于 mock 模式：任意 4 位数字都能通过校验，任何人可以注册任意邮箱。"
                        + "当前 profile={}，只允许本地使用。", active);
            }
            if (seedDemo) {
                log.warn("⚠ 演示数据写入已开启：启动时会创建 good / xiaoba / yuanhua 三个弱口令账号。"
                        + "当前 profile={}，只允许本地使用。", active);
            }
            return;
        }

        if (mockVerification || seedDemo) {
            String detail = (mockVerification ? "app.verification.mock-accept-any-code=true" : "")
                    + (mockVerification && seedDemo ? "，" : "")
                    + (seedDemo ? "app.bootstrap.seed-demo-data=true" : "");
            throw new IllegalStateException("拒绝启动：当前 profile=" + active
                    + " 不是开发环境，但开启了危险开关 [" + detail + "]。"
                    + "这两个开关会让任何人都能注册任意邮箱、并往库里写弱口令演示账号。"
                    + "确认是本地开发请加 --spring.profiles.active=local；"
                    + "如果是生产环境，请把上述配置改回 false。");
        }
    }
}