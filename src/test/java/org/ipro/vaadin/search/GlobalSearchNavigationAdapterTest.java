package org.ipro.vaadin.search;

import org.ip.model.Nomenclature;
import org.ipro.form.coordinator.FormCoordinator;
import org.ipro.search.GlobalSearchCatalog;
import org.ipro.search.GlobalSearchMatchKind;
import org.ipro.search.GlobalSearchResult;
import org.ipro.search.GlobalSearchSource;
import org.ipro.search.GlobalSearchTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class GlobalSearchNavigationAdapterTest {

    private FormCoordinator formCoordinator;
    private GlobalSearchNavigationAdapter adapter;

    @BeforeEach
    void setUp() {
        GlobalSearchCatalog catalog = GlobalSearchTestSupport.catalog();
        formCoordinator = mock(FormCoordinator.class);
        adapter = new GlobalSearchNavigationAdapter(catalog, formCoordinator);
    }

    @Test
    void opensRegisteredResultThroughFormCoordinator() {
        GlobalSearchSource source = GlobalSearchTestSupport.catalog()
            .requireSource(Nomenclature.class);
        adapter.open(new GlobalSearchResult(
            source.declarationOrder(), source.groupTitle(), Nomenclature.class, 17L,
            "N-017 Гайка", GlobalSearchMatchKind.PREFIX, "code"));

        verify(formCoordinator).openItemForm(Nomenclature.class, 17L, null);
    }

    @Test
    void rejectsUnknownEntity() {
        assertThatThrownBy(() -> adapter.open(new GlobalSearchResult(
            0, "Неизвестная сущность", String.class, 1L,
            "x", GlobalSearchMatchKind.EXACT, "value")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("незарегистрированную сущность");
    }

    @Test
    void rejectsStaleCatalogOrderOrTitle() {
        GlobalSearchSource source = GlobalSearchTestSupport.catalog()
            .requireSource(Nomenclature.class);
        assertThatThrownBy(() -> adapter.open(new GlobalSearchResult(
            source.declarationOrder() + 1, source.groupTitle(), Nomenclature.class, 17L,
            "N-017 Гайка", GlobalSearchMatchKind.PREFIX, "code")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("не соответствует текущему каталогу");
    }
}
