package com.biecuoguo.media.api;

import java.io.InputStream;
import java.time.OffsetDateTime;

public final class MediaDtos {
    private MediaDtos() {}

    public record UploadResponse(
            Long id,
            String url,
            String objectKey,
            String contentType,
            Long sizeBytes,
            String purpose,
            OffsetDateTime createdAt
    ) {}

    public record StoredObject(
            InputStream stream,
            String contentType,
            Long sizeBytes
    ) {}
}
