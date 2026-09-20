package org.ipro.form.registry;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты SELECTION-ветки {@link FormRegistry} (Этап 1): кастомные фабрики диалога
 * и data-варианты (наборы колонок).
 */
class FormRegistrySelectionTest {

    @Test
    void registerAndFindSelectionForm() {
        FormRegistry registry = new FormRegistry();
        FormFactory factory = ctx -> null;

        assertThat(registry.findSelectionForm(String.class, "compact")).isNull();

        registry.registerSelectionForm(String.class, "compact", factory);

        assertThat(registry.findSelectionForm(String.class, "compact")).isSameAs(factory);
        assertThat(registry.find(String.class, FormType.SELECTION, "compact")).isSameAs(factory);
        assertThat(registry.has(String.class, FormType.SELECTION, "compact")).isTrue();
        assertThat(registry.findSelectionForm(String.class, null)).isNull();
    }

    @Test
    void registerSelectionFormByEnumUsesLowerCaseKey() {
        FormRegistry registry = new FormRegistry();
        FormFactory factory = ctx -> null;

        registry.registerSelectionForm(String.class, Variant.COMPACT, factory);

        assertThat(registry.findSelectionForm(String.class, "compact")).isSameAs(factory);
        assertThat(registry.findSelectionForm(String.class, "COMPACT")).isNull();
    }

    @Test
    void registerAndGetSelectionColumns() {
        FormRegistry registry = new FormRegistry();

        assertThat(registry.getSelectionColumns(String.class, "compact")).isNull();

        registry.registerSelectionColumns(String.class, "compact",
            SelectionColumnsDef.of(List.of("code", "name"), "Кратко"));

        SelectionColumnsDef def = registry.getSelectionColumns(String.class, "compact");
        assertThat(def.columns()).containsExactly("code", "name");
        assertThat(def.title()).isEqualTo("Кратко");
        assertThat(registry.getSelectionColumns(String.class, null)).isNull();
    }

    @Test
    void clearDropsSelectionRegistrations() {
        FormRegistry registry = new FormRegistry();
        registry.registerSelectionForm(String.class, "compact", ctx -> null);
        registry.registerSelectionColumns(String.class, "compact", SelectionColumnsDef.of("code"));

        registry.clear();

        assertThat(registry.findSelectionForm(String.class, "compact")).isNull();
        assertThat(registry.getSelectionColumns(String.class, "compact")).isNull();
    }

    @Test
    void variantRowsAndListResolution() {
        FormRegistry registry = new FormRegistry();
        org.ipro.form.builder.ContextFilterField shared =
            org.ipro.form.builder.ContextFilterField.auto("code", "Код");
        org.ipro.form.builder.ContextFilterField extra =
            org.ipro.form.builder.ContextFilterField.auto("name", "Имя");

        assertThat(registry.getVariantContextFilters(String.class, FormType.LIST, "v")).isEmpty();

        registry.registerContextFilters(String.class, List.of(shared));
        registry.registerVariantContextFilters(String.class, FormType.LIST, "v", List.of(extra));

        // Ряд варианта бьёт общий (замена)
        assertThat(registry.resolveListContextFilters(String.class, "v")).containsExactly(extra);
        // Без своего ряда — общий
        assertThat(registry.resolveListContextFilters(String.class, null)).containsExactly(shared);
        assertThat(registry.resolveListContextFilters(String.class, "other")).containsExactly(shared);

        // Перечисление для сборки allListVariants(): детерминированный порядок
        assertThat(registry.getVariantContextFilters(String.class, FormType.LIST))
            .containsOnlyKeys("v");
        assertThat(registry.getVariantContextFilters(String.class, FormType.SELECTION)).isEmpty();

        registry.clear();
        assertThat(registry.resolveListContextFilters(String.class, "v")).isEmpty();
    }

    private enum Variant {
        COMPACT
    }
}
