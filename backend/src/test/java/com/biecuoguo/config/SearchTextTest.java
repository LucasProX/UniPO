package com.biecuoguo.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchTextTest {

    @Test
    void wrapsQueryAsPhraseSoOperatorsAreLiteral() {
        assertThat(SearchText.phrase("  四级 +报名 ")).isEqualTo("\"四级 +报名\"");
        assertThat(SearchText.phrase("a\"b\" OR 1")).isEqualTo("\"a b OR 1\"");
    }

    @Test
    void rejectsTooShortOrTooLong() {
        assertThatThrownBy(() -> SearchText.normalize("四")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SearchText.normalize(" \" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SearchText.normalize(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SearchText.normalize("字".repeat(41))).isInstanceOf(IllegalArgumentException.class);
        assertThat(SearchText.normalize("字".repeat(40))).hasSize(40);
    }
}
