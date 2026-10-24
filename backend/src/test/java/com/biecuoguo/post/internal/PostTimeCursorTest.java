package com.biecuoguo.post.internal;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostTimeCursorTest {

    @Test
    void roundTrips() {
        var cursor = new PostService.TimeCursor(LocalDateTime.of(2026, 10, 24, 13, 5, 7), 42);
        assertThat(PostService.TimeCursor.parse(cursor.encode())).isEqualTo(cursor);
        assertThat(PostService.TimeCursor.parse(" ")).isNull();
    }

    @Test
    void rejectsMalformed() {
        assertThatThrownBy(() -> PostService.TimeCursor.parse("x|1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PostService.TimeCursor.parse("2026-10-24T13:05|1|2")).isInstanceOf(IllegalArgumentException.class);
    }
}
