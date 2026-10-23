package com.biecuoguo;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 模块边界的守门员（ADR-0001 D5 / ADR-0002）。
 *
 * <p>为什么要有这个：模块化不是把包名改一改，而是「依赖方向错了就 CI 失败」。
 * 没有这条测试，半年后一定有人让 Controller 直接调 Mapper、让 domain 反过来依赖 service，
 * 到那时再想拆服务就拆不动了。
 *
 * <p>注意包名必须写全限定前缀（com.biecuoguo.xxx..）。用 `..service..` 这种写法会把
 * org.springframework.web.socket 之类也匹配进去，规则看着对、其实拦的是框架类。
 *
 * <p>现在的约束是**在现有代码上立起来的**，不是理想态：每搬一个模块，规则就收紧一次。
 * 目标形态见 docs/adr/0002-module-boundaries.md。
 */
class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.biecuoguo");

    private static final String WEB = "com.biecuoguo.web..";
    private static final String SERVICE = "com.biecuoguo.service..";
    private static final String MAPPER = "com.biecuoguo.mapper..";
    private static final String DOMAIN = "com.biecuoguo.domain..";
    private static final String SECURITY = "com.biecuoguo.security..";
    private static final String REALTIME = "com.biecuoguo.realtime..";
    private static final String STATE = "com.biecuoguo.state..";

    @Test
    void webDoesNotTalkToMappersDirectly() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(WEB)
                .should().dependOnClassesThat().resideInAPackage(MAPPER)
                .because("Controller 只能经 Service 访问数据；直接调 Mapper 会把业务规则漏到 HTTP 层");
        rule.check(CLASSES);
    }

    @Test
    void serviceDoesNotDependOnWeb() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(SERVICE)
                .should().dependOnClassesThat().resideInAPackage(WEB)
                .because("Service 不能碰 HTTP；错误码/响应包由 web 层统一处理");
        rule.check(CLASSES);
    }

    @Test
    void domainIsFrameworkFree() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(DOMAIN)
                .should().dependOnClassesThat().resideInAnyPackage(
                        SERVICE, WEB, SECURITY, REALTIME, STATE, "org.springframework..")
                .because("领域对象只表达数据与不变量，不依赖框架和上层");
        rule.check(CLASSES);
    }

    @Test
    void mappersOnlyDependOnDomain() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(MAPPER)
                .should().dependOnClassesThat().resideInAnyPackage(SERVICE, WEB, SECURITY, REALTIME)
                .because("Mapper 是数据访问层，不能反向依赖业务/HTTP");
        rule.check(CLASSES);
    }

    @Test
    void moduleInternalsAreNotAccessedFromOutside() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("com.biecuoguo.media..")
                .should().dependOnClassesThat().resideInAPackage("com.biecuoguo.media.internal..")
                .because("模块内部实现对其他模块不可见，跨模块只能依赖 api 包（ADR-0002）");
        rule.check(CLASSES);
    }

    @Test
    void authenticationInternalsAreNotAccessedFromOutside() {
        noClasses().that().resideOutsideOfPackage("com.biecuoguo.auth..")
                .should().dependOnClassesThat().resideInAPackage("com.biecuoguo.auth.internal..")
                .because("身份状态跨模块只经 AccountSecurityApi，不能绕过统一失效校验")
                .check(CLASSES);
    }

    @Test
    void postInternalsAreNotAccessedFromOutside() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("com.biecuoguo.post..")
                .should().dependOnClassesThat().resideInAPackage("com.biecuoguo.post.internal..")
                .because("post 内部实现对其他模块不可见，跨模块只能依赖 api（ADR-0002）");
        rule.check(CLASSES);
    }

    @Test
    void messageInternalsAreNotAccessedFromOutside() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("com.biecuoguo.message..")
                .should().dependOnClassesThat().resideInAPackage("com.biecuoguo.message.internal..")
                .because("message 内部实现对其他模块不可见，跨模块只能依赖 api（ADR-0002）");
        rule.check(CLASSES);
    }

    @Test
    void pushInternalsAreNotAccessedFromOutside() {
        noClasses().that().resideOutsideOfPackage("com.biecuoguo.push..")
                .should().dependOnClassesThat().resideInAPackage("com.biecuoguo.push.internal..")
                .because("推送进度与设备凭据只经 push.api 访问")
                .check(CLASSES);
    }

    @Test
    void taxonomyInternalsAreNotAccessedFromOutside() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("com.biecuoguo.taxonomy..")
                .should().dependOnClassesThat().resideInAPackage("com.biecuoguo.taxonomy.internal..")
                .because("taxonomy 内部实现对其他模块不可见，跨模块只能依赖 api（ADR-0002）");
        rule.check(CLASSES);
    }

    @Test
    void spotlightInternalsAreNotAccessedFromOutside() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("com.biecuoguo.spotlight..")
                .should().dependOnClassesThat().resideInAPackage("com.biecuoguo.spotlight.internal..")
                .because("spotlight 内部实现对外不可见，跨模块只能依赖 api（ADR-0002）");
        rule.check(CLASSES);
    }

    @ParameterizedTest
    @ValueSource(strings = {"activity", "achievement", "growth", "wallet", "notification"})
    void growthLoopInternalsAreNotAccessedFromOutside(String module) {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("com.biecuoguo." + module + "..")
                .should().dependOnClassesThat().resideInAPackage("com.biecuoguo." + module + ".internal..")
                .because(module + " 内部实现对外不可见，跨模块只能依赖 api（ADR-0002）");
        rule.check(CLASSES);
    }

    @Test
    void realtimeDoesNotDependOnBusinessServices() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(REALTIME)
                .should().dependOnClassesThat().resideInAnyPackage(SERVICE, WEB)
                .because("实时通道是被业务调用的底层能力；MessageService 依赖 realtime，反过来不行");
        rule.check(CLASSES);
    }
}
