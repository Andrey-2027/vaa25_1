package org.ipro.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalSearchHeaderTest {

    private GlobalSearchHeader header;

    @BeforeEach
    void setUp() {
        header = new GlobalSearchHeader(
            Mockito.mock(GlobalSearchService.class),
            Mockito.mock(GlobalSearchNavigationAdapter.class));
    }

    @Test
    void startsWithEmptySearchAndHiddenResults() {
        assertThat(header.searchField().getValue()).isEmpty();
        assertThat(header.results().isVisible()).isFalse();
        assertThat(header.searchField().getAriaLabel()).isEqualTo("Глобальный поиск");
    }

    @Test
    void shortTermShowsValidationMessageWithoutCallingService() {
        header.searchField().setValue("а");

        assertThat(header.results().isVisible()).isTrue();
        assertThat(header.results().getChildren())
            .anyMatch(component -> component.getElement().getText().contains("минимум 2"));
    }
}
