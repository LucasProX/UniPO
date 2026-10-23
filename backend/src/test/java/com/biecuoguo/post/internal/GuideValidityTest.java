package com.biecuoguo.post.internal;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuideValidityTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 23, 18, 0);

    @Test
    void postsWithoutExpiryNeverExpire() {
        assertFalse(PostService.isExpired(new Post(), NOW));
    }

    @Test
    void expiryIsInclusiveOfTheExactMoment() {
        Post post = new Post();
        post.setExpiresAt(NOW.plusSeconds(1));
        assertFalse(PostService.isExpired(post, NOW));
        post.setExpiresAt(NOW);
        assertTrue(PostService.isExpired(post, NOW), "到点即过期");
        post.setExpiresAt(NOW.minusDays(1));
        assertTrue(PostService.isExpired(post, NOW));
    }
}
