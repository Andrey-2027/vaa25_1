package org.ipro.filter;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SeedFilterTest {

    @Test
    void readsLegacySingleFilter() {
        SeedFilter filter = SeedFilter.fromParameters(Map.of(
            "seedFilter", Map.of("path", "journal.id", "value", 10L)));

        assertThat(filter).isEqualTo(new SeedFilter("journal.id", 10L));
        assertThat(SeedFilter.allFromParameters(Map.of(
            "seedFilter", Map.of("path", "journal.id", "value", 10L))))
            .containsExactly(filter);
    }

    @Test
    void readsSeveralFiltersAndKeepsLegacyCompatibility() {
        List<SeedFilter> filters = SeedFilter.allFromParameters(Map.of(
            "seedFilters", List.of(
                Map.of("path", "matrix.id", "value", 5L),
                Map.of("path", "type", "value", "A")),
            "seedFilter", Map.of("path", "active", "value", true)));

        assertThat(filters).containsExactly(
            new SeedFilter("matrix.id", 5L),
            new SeedFilter("type", "A"),
            new SeedFilter("active", true));
    }

    @Test
    void readsShortMapNotation() {
        assertThat(SeedFilter.allFromParameters(Map.of(
            "seedFilters", Map.of("matrix.id", 5L, "type", "A"))))
            .containsExactlyInAnyOrder(
                new SeedFilter("matrix.id", 5L),
                new SeedFilter("type", "A"));
    }

    @Test
    void invalidOrMissingParametersProduceNoFilters() {
        assertThat(SeedFilter.allFromParameters(null)).isEmpty();
        assertThat(SeedFilter.allFromParameters(Map.of(
            "seedFilter", Map.of("value", 1L)))).isEmpty();
    }
}
