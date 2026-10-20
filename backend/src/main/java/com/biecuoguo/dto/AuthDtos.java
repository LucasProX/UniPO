package com.biecuoguo.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthDtos {
    private AuthDtos() {}

    /**
     * 注册请求。
     *
     * <p>开发阶段刻意放宽：{@code email} 就是「账号」，可以是任意字符串（不强制邮箱格式，
     * 内部统一转小写后判重）；{@code nickname} / {@code confirmPassword} / {@code verificationCode}
     * 全部可选。
     *
     * <p>{@code verificationCode} 只在关闭 mock 验证码（即真实环境）时才必填——那时
     * {@code VerificationService} 会正常校验并拒绝空值。开发环境留空即可注册。
     *
     * <p>{@code account} 是 {@code email} 的别名，App / 脚本可以直接传 {@code account}。
     */
    public record RegisterRequest(
            @JsonAlias({"account", "username"}) @NotBlank String email,
            @NotBlank String password,
            String confirmPassword,
            String nickname,
            String verificationCode
    ) {}

    /**
     * 登录请求。{@code email} 同样是「账号」，可以传 {@code account} / {@code username}。
     * 不做格式校验——账号是什么格式由注册时决定。
     */
    public record LoginRequest(
            @JsonAlias({"account", "username"}) @NotBlank String email,
            @NotBlank String password
    ) {}

    public record AuthResponse(String token, UserProfile user) {}

    public record VerificationRequest(@Email @NotBlank String email, @NotBlank @Pattern(regexp = "register|reset-password") String purpose) {}

    public record VerifyCodeRequest(@Email @NotBlank String email, @NotBlank @Pattern(regexp = "register|reset-password") String purpose, @NotBlank @Size(min = 4, max = 4) String code) {}

    public record AvailabilityResponse(boolean available) {}

    /**
     * 重置密码。{@code verificationCode} 与 {@code confirmPassword} 可选：
     * 前者在 mock 模式下不校验，后者给 App 端省略一格输入框的余地。
     */
    public record ResetPasswordRequest(
            @JsonAlias({"account", "username"}) @NotBlank String email,
            String verificationCode,
            @NotBlank String password,
            String confirmPassword
    ) {}
}