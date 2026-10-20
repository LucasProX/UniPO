package com.biecuoguo.spotlight.api;

import java.time.OffsetDateTime;

public final class SpotlightDtos {
    private SpotlightDtos() {
    }

    /** 公开读取与后台列表共用的形状。 */
    public record SpotlightView(
            Long id,
            String title,
            String subtitle,
            String badge,
            String imageUrl,
            String linkUrl,
            String source,
            Integer weight,
            Boolean enabled,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            OffsetDateTime createdAt
    ) {
    }

    /**
     * 后台新增/编辑请求。{@code source} 只允许 MANUAL / ALGORITHM；
     * 算法位可以带 {@code referencePostId}，手动位一般留空。
     */
    public record UpsertRequest(
            String title,
            String subtitle,
            String badge,
            String imageUrl,
            String linkUrl,
            String source,
            Long referencePostId,
            Integer weight,
            Boolean enabled,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt
    ) {
    }
}
