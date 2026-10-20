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
                             Auth auth, Long defaultSchoolId) {
    public record Cors(String allowedOrigins) {}
    /**
     * JWT 配置。
     *
     * <p>{@code expirationMinutes} 是 access token 的有效期（默认 120 分钟），
     * {@code refreshExpirationMinutes} 是 refresh token 的（默认 20160 分钟 = 14 天）。
     * access 故意短：客户端用 refresh 换新的，见 {@code POST /api/auth/refresh}。
     */
    public record Jwt(String secret, long expirationMinutes, long refreshExpirationMinutes) {}
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
    /**
     * Web 会话 Cookie 配置。
     *
     * <p>Cookie 里只放不透明 session id，登录态在服务端（Redis / 内存）。
     * 生产必须 {@code cookieSecure=true}（只走 HTTPS）；本地 http 由 application-local.yml 关掉。
     *
     * <p>{@code csrfHeaderName} 是前端要回填的请求头，值来自非 HttpOnly 的 csrf Cookie。
     */
    public record Auth(String sessionCookieName, String csrfCookieName, String csrfHeaderName,
                       int sessionTtlMinutes, boolean cookieSecure, String cookieSameSite) {}
}
