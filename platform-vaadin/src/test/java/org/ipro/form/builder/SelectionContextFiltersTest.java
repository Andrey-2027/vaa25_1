package org.ipro.form.builder;

import com.vaadin.flow.component.Component;
import org.ipro.form.registry.FormRegistry;
import org.ipro.crud.LookupService;
import org.ipro.form.SelectionForm;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Тесты подключения ряда фильтров к диалогу выбора (Этап 1б): та же декларация,
 * что у списка; без декларации диалог без панели.
 */
class SelectionContextFiltersTest {

    private final MetadataResolver metadataResolver = new MetadataResolver();
    private final FormRegistry registry = new FormRegistry();

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void attachAddsPanelAndFixedFilters() {
        registry.registerContextFilters(Unit.class,
            List.of(ContextFilterField.auto("code", "Код").allListVariants()));
        SelectionForm form = mock(SelectionForm.class);

        SelectionContextFilters.attach(form, Unit.class, null, Map.of("kind", "A"),
            registry, metadataResolver, mock(LookupService.class),
            mock(SelectionFormAssembler.class));

        verify(form).setFixedFilters(Map.of("kind", "A"));
        verify(form).setHeaderRow(any(Component.class));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void attachWithoutMarkedFieldsShowsNoPanel() {
        registry.registerContextFilters(Unit.class,
            List.of(ContextFilterField.auto("code", "Код")));
        SelectionForm form = mock(SelectionForm.class);

        SelectionContextFilters.attach(form, Unit.class, null, Map.of(),
            registry, metadataResolver, mock(LookupService.class),
            mock(SelectionFormAssembler.class));

        verify(form).setFixedFilters(anyMap());
        verify(form, never()).setHeaderRow(any(Component.class));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void attachWithoutDeclarationOnlySetsFixed() {
        SelectionForm form = mock(SelectionForm.class);

        SelectionContextFilters.attach(form, Unit.class, null, Map.of(),
            registry, metadataResolver, mock(LookupService.class),
            mock(SelectionFormAssembler.class));

        verify(form).setFixedFilters(anyMap());
        verify(form, never()).setHeaderRow(any(Component.class));
    }

    @EntityMetadata(listFormTitle = "ЕИ", selectionFormTitle = "Выбор ЕИ")
    static class Unit {
        @FieldMetadata(label = "Код", grid = @GridColumn(order = 1))
        String code;
    }
}
