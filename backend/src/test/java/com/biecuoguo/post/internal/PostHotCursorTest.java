package com.biecuoguo.post.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostHotCursorTest {

    @Test
    void roundTripsTheExactScore() {
        double score = 298.38793981481484;
        var cursor = new PostService.HotCursor(false, score, 33);
        var parsed = PostService.HotCursor.parse(cursor.encode());
        assertThat(parsed).isEqualTo(cursor);
        assertThat(Double.compare(parsed.hotScore(), score)).isZero();
        assertThat(PostService.HotCursor.parse(" ")).isNull();
    }

    @Test
    void readsTheSortKeyFromThePost() {
        Post post = new Post();
        post.setId(7L);
        post.setPinned(true);
        post.setHotScore(296.41);
        assertThat(PostService.HotCursor.of(post)).isEqualTo(new PostService.HotCursor(true, 296.41, 7));
    }

    @Test
    void rejectsMalformedAndLegacyCursors() {
        assertThatThrownBy(() -> PostService.HotCursor.parse("not-a-cursor")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PostService.HotCursor.parse("yes|1.0|2")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PostService.HotCursor.parse("false|NaN|2")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PostService.HotCursor.parse("false|Infinity|2")).isInstanceOf(IllegalArgumentException.class);
        // 2026-10-28 前的七段热度游标
        assertThatThrownBy(() -> PostService.HotCursor.parse("false|false|false|3|1|2026-10-25T22:45:20|29"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
