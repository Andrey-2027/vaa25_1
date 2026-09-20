package org.ipro.form.builder;

import org.ipro.form.registry.FormRegistry;
import org.ipro.form.registry.SelectionColumnsDef;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты приоритета колонок Формы Выбора (Этап 1):
 * field columns → вариант → selectColumns → listColumns/grid.
 */
class SelectionColumnResolverTest {

    private final MetadataResolver metadataResolver = new MetadataResolver();
    private final FormRegistry registry = new FormRegistry();

    @Test
    void defaultUsesSelectColumns() {
        SelectionFormAssembler.ResolvedSelection resolved =
            SelectionColumnResolver.resolve(metadataResolver, registry, Unit.class, null, null);

        assertThat(resolved.columns()).extracting(p -> p.getKey())
            .containsExactly("code", "name");
        assertThat(resolved.title()).isEqualTo("Выбор ЕИ");
    }

    @Test
    void defaultWithoutSelectColumnsFallsBackToGrid() {
        SelectionFormAssembler.ResolvedSelection resolved =
            SelectionColumnResolver.resolve(metadataResolver, registry, Warehouse.class, null, null);

        assertThat(resolved.columns()).extracting(p -> p.getKey())
            .containsExactly("name", "code");
    }

    @Test
    void registeredVariantBeatsSelectColumns() {
        registry.registerSelectionColumns(Unit.class, "compact",
            SelectionColumnsDef.of(List.of("code"), "Кратко"));

        SelectionFormAssembler.ResolvedSelection resolved =
            SelectionColumnResolver.resolve(metadataResolver, registry, Unit.class, "compact", null);

        assertThat(resolved.columns()).extracting(p -> p.getKey()).containsExactly("code");
        assertThat(resolved.title()).isEqualTo("Кратко");
    }

    @Test
    void variantWithoutTitleKeepsDefaultTitle() {
        registry.registerSelectionColumns(Unit.class, "compact", SelectionColumnsDef.of("name"));

        SelectionFormAssembler.ResolvedSelection resolved =
            SelectionColumnResolver.resolve(metadataResolver, registry, Unit.class, "compact", null);

        assertThat(resolved.columns()).extracting(p -> p.getKey()).containsExactly("name");
        assertThat(resolved.title()).isEqualTo("Выбор ЕИ");
    }

    @Test
    void fieldColumnsOverrideBeatsVariant() {
        registry.registerSelectionColumns(Unit.class, "compact",
            SelectionColumnsDef.of(List.of("code"), "Кратко"));

        SelectionFormAssembler.ResolvedSelection resolved = SelectionColumnResolver.resolve(
            metadataResolver, registry, Unit.class, "compact", new String[]{"name"});

        assertThat(resolved.columns()).extracting(p -> p.getKey()).containsExactly("name");
    }

    @Test
    void unknownVariantReturnsNullForStrictError() {
        assertThat(SelectionColumnResolver.resolve(
            metadataResolver, registry, Unit.class, "nope", null)).isNull();
    }

    @EntityMetadata(listFormTitle = "ЕИ", selectionFormTitle = "Выбор ЕИ",
        selectColumns = {"code", "name"})
    static class Unit {
        @FieldMetadata(label = "Код", grid = @GridColumn(order = 1))
        String code;
        @FieldMetadata(label = "Наименование", grid = @GridColumn(order = 2))
        String name;
        @FieldMetadata(label = "Комментарий", grid = @GridColumn(order = 3))
        String comment;
    }

    @EntityMetadata(listFormTitle = "Склады", selectionFormTitle = "Выбор склада")
    static class Warehouse {
        @FieldMetadata(label = "Код", grid = @GridColumn(order = 2))
        String code;
        @FieldMetadata(label = "Имя", grid = @GridColumn(order = 1))
        String name;
        @FieldMetadata(label = "Скрытое", grid = @GridColumn(visible = false))
        String hidden;
    }
}
