package com.biecuoguo.dto;

import java.time.LocalDateTime;

public final class MediaDtos {
    private MediaDtos() {}

    public record UploadResponse(
            Long id,
            String url,
            String objectKey,
            String contentType,
            Long sizeBytes,
            String purpose,
            LocalDateTime createdAt
    ) {}
}
