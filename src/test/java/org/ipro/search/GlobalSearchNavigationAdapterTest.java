package org.ipro.search;

import org.ip.config.GlobalSearchApplicationConfig;
import org.ip.model.Nomenclature;
import org.ipro.form.coordinator.FormCoordinator;
import org.ipro.metadata.MetadataResolver;
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
        GlobalSearchCatalog catalog = new GlobalSearchCatalog(
            new GlobalSearchApplicationConfig().globalSearchConfig(), new MetadataResolver());
        formCoordinator = mock(FormCoordinator.class);
        adapter = new GlobalSearchNavigationAdapter(catalog, formCoordinator);
    }

    @Test
    void opensRegisteredResultThroughFormCoordinator() {
        adapter.open(new GlobalSearchResult(
            0, "Номенклатура", Nomenclature.class, 17L,
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
        assertThatThrownBy(() -> adapter.open(new GlobalSearchResult(
            99, "Номенклатура", Nomenclature.class, 17L,
            "N-017 Гайка", GlobalSearchMatchKind.PREFIX, "code")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("не соответствует текущему каталогу");
    }
}
