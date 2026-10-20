package com.biecuoguo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
 /**
  * 默认学校 id。
  *
  * <p>只有两种场景会用到它：未登录访客看 feed、新注册用户还没选学校。
  * 以前这两处各自硬编码 {@code 1L}，访客那边更糟——随便挑一个 active 用户借人家的
  * school_id（见 docs/ISSUES.md D-3），同一个请求两次可能看到不同内容。
  */
 public record AppProperties(Cors cors, Jwt jwt, Bootstrap bootstrap, Minio minio, Verification verification,
                             Long defaultSchoolId) {
    public record Cors(String allowedOrigins) {}
    public record Jwt(String secret, long expirationMinutes) {}
    /**
     * 启动引导配置。
     *
     * <p>{@code adminPassword} 只在**管理员账号不存在时**用一次，之后再也不读——
     * 否则每次重启都会把线上管理员的密码覆盖回 compose 里的默认值。
     *
     * <p>{@code seedDemoData} 控制是否写入演示账号（good / xiaoba / yuanhua）和演示数据。
     * 默认 false：生产库里不应该有这些账号。本地用 application-local.yml 打开。
     */
    public record Bootstrap(String adminEmail, String adminPassword, boolean seedDemoData) {}
    public record Minio(String endpoint, String publicEndpoint, String accessKey, String secretKey, String bucket, boolean publicRead) {}
    public record Verification(boolean mockAcceptAnyCode) {}
}
