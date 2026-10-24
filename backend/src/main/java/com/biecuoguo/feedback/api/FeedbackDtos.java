package com.biecuoguo.feedback.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public final class FeedbackDtos {
    private FeedbackDtos() {
    }

    /** category：bug / suggestion / content / other。 */
    public record CreateFeedbackRequest(
            @NotBlank String category,
            @NotBlank @Size(min = 5, max = 1000) String content,
            @Size(max = 40) String appVersion,
            @Size(max = 60) String device
    ) {}

    public record FeedbackView(long id, String category, String content, String appVersion, String device,
                               String userUid, String userNickname, String status, OffsetDateTime createdAt) {}

    public record FeedbackPage(java.util.List<FeedbackView> items, Long nextBeforeId) {}
}
