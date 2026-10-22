package com.biecuoguo.message.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

/** Administrative delivery metadata only: no message bodies, participants or device credentials. */
public final class OutboxOperationsDtos {
    private OutboxOperationsDtos() {}

    public record DeliveryView(
            long id,
            String eventId,
            long messageId,
            String channel,
            String state,
            int attempts,
            String lastError,
            OffsetDateTime createdAt,
            OffsetDateTime availableAt,
            OffsetDateTime leaseUntil,
            OffsetDateTime completedAt
    ) {}

    public record DeliveryPage(List<DeliveryView> items, String nextCursor, boolean hasMore, int limit) {}

    public record RetryRequest(@NotNull @Min(0) Integer expectedAttempts,
                               @NotBlank @Size(max = 200) String reason) {}
}
