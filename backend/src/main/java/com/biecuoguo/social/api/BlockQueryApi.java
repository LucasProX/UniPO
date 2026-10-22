package com.biecuoguo.social.api;

import java.util.Set;

/** Read-only block relationship queries exposed to other domains. */
public interface BlockQueryApi {

    /** Users blocked by either side of the viewer relationship. */
    Set<Long> blockedUserIds(Long viewerId);

    /** Whether either user has blocked the other. */
    boolean isBlockedEitherWay(Long firstUserId, Long secondUserId);
}
