package com.biecuoguo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
 public record AppProperties(Cors cors, Jwt jwt, Bootstrap bootstrap, Minio minio, Verification verification) {
    public record Cors(String allowedOrigins) {}
    public record Jwt(String secret, long expirationMinutes) {}
    public record Bootstrap(String adminEmail, String adminPassword) {}
    public record Minio(String endpoint, String publicEndpoint, String accessKey, String secretKey, String bucket, boolean publicRead) {}
    public record Verification(boolean mockAcceptAnyCode) {}
}
