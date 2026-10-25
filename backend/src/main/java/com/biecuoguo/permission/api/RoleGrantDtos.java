package com.biecuoguo.permission.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

public final class RoleGrantDtos {
    private RoleGrantDtos() {
    }

    /** scopeId 在 PLATFORM 时省略；expiresAt 省略时默认到当前学年结束（8 月 31 日）。 */
    public record GrantRequest(@NotBlank String userUid, @NotNull Role role, Long scopeId,
                               @NotBlank @Size(max = 200) String reason, OffsetDateTime expiresAt) {}

    public record RevokeRequest(@NotBlank @Size(max = 200) String reason) {}

    public record GrantView(long id, String userUid, String userNickname, String role, String roleLabel,
                            String scopeType, Long scopeId, String scopeName, String reason,
                            String grantedByUid, OffsetDateTime startsAt, OffsetDateTime expiresAt,
                            OffsetDateTime revokedAt, String revokeReason, boolean active) {}

    /** 给客户端的入口清单：平台角色、有效授权，以及每个能力生效的范围。 */
    public record CapabilitiesView(String platformRole, List<GrantView> grants, List<CapabilityScope> capabilities) {}

    public record CapabilityScope(String capability, List<String> scopes) {}
}
