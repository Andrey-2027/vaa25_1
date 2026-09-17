package org.ipro.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GlobalSearchRequestTest {

    @Test
    void trimsTermAndClampsLimitsToSafeMaximums() {
        GlobalSearchRequest request = new GlobalSearchRequest(
            "  гайка  ", GlobalSearchRequest.MAX_PER_SOURCE_LIMIT + 100,
            GlobalSearchRequest.MAX_TOTAL_LIMIT + 100);

        assertThat(request.term()).isEqualTo("гайка");
        assertThat(request.perSourceLimit()).isEqualTo(GlobalSearchRequest.MAX_PER_SOURCE_LIMIT);
        assertThat(request.totalLimit()).isEqualTo(GlobalSearchRequest.MAX_TOTAL_LIMIT);
        assertThat(request.isTooShort()).isFalse();
    }

    @Test
    void nullAndOneCharacterTermsAreTooShort() {
        assertThat(GlobalSearchRequest.of(null).term()).isEmpty();
        assertThat(GlobalSearchRequest.of(null).isTooShort()).isTrue();
        assertThat(GlobalSearchRequest.of("а").isTooShort()).isTrue();
        assertThat(GlobalSearchRequest.of("аб").isTooShort()).isFalse();
    }

    @Test
    void nonPositiveLimitsAreRejected() {
        assertThatThrownBy(() -> new GlobalSearchRequest("abc", 0, 10))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("perSourceLimit");
        assertThatThrownBy(() -> new GlobalSearchRequest("abc", 1, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("totalLimit");
    }
}
