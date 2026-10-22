package com.biecuoguo.social.api;

import java.util.Set;

/** Block relationship queries and pair-level serialization exposed to other domains. */
public interface BlockQueryApi {

    /** Lock active participants in ID order, then their pair; requires an active database transaction. */
    void lockPair(Long firstUserId, Long secondUserId);

    /**
     * Current relationship read on the caller's connection. The caller must already hold lockPair
     * for these participants in the same active transaction; no additional connection is borrowed.
     */
    boolean isBlockedInLockedPair(Long firstUserId, Long secondUserId);

    /** Fresh committed users blocked by either side; does not inherit a caller's snapshot/cache. */
    Set<Long> blockedUserIds(Long viewerId);

    /** Fresh committed authority at the query point; already transmitted data cannot be recalled. */
    boolean isBlockedEitherWay(Long firstUserId, Long secondUserId);
}
