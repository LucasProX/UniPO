package com.biecuoguo.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthDtos {
    private AuthDtos() {}

    public record RegisterRequest(
            @Email @NotBlank String email,
            @NotBlank @Size(min = 6, max = 64) String password,
            @NotBlank @Size(min = 6, max = 64) String confirmPassword,
            @NotBlank @Size(max = 40) String nickname,
            @NotBlank @Size(min = 4, max = 4) String verificationCode
    ) {}

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {}

    public record AuthResponse(String token, UserProfile user) {}

    public record VerificationRequest(@Email @NotBlank String email, @NotBlank @Pattern(regexp = "register|reset-password") String purpose) {}

    public record VerifyCodeRequest(@Email @NotBlank String email, @NotBlank @Pattern(regexp = "register|reset-password") String purpose, @NotBlank @Size(min = 4, max = 4) String code) {}

    public record AvailabilityResponse(boolean available) {}

    public record ResetPasswordRequest(
            @Email @NotBlank String email,
            @NotBlank @Size(min = 4, max = 4) String verificationCode,
            @NotBlank @Size(min = 6, max = 64) String password,
            @NotBlank @Size(min = 6, max = 64) String confirmPassword
    ) {}
}
