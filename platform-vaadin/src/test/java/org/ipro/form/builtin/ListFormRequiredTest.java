package org.ipro.form.builtin;

import org.ipro.form.builder.ContextFilterField;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Тесты обязательного контекста списка (Этап 1б): без журнала «Создать» погашена,
 * с журналом — горит; грид при этом всегда показывает всё.
 */
class ListFormRequiredTest {

    private ListForm<Unit, Long> form;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        EntityMetadataInfo meta = mock(EntityMetadataInfo.class);
        when(meta.getEntityClass()).thenReturn((Class) Unit.class);
        when(meta.getListColumnPaths()).thenReturn(List.of());
        org.ipro.filtergrid.FilterGrid<Unit> filterGrid = mock(org.ipro.filtergrid.FilterGrid.class);
        org.ipro.filtergrid.FilterGrid<Unit> grid = filterGrid;
        when(grid.getGrid()).thenReturn(mock(com.vaadin.flow.component.grid.Grid.class,
            org.mockito.Mockito.RETURNS_DEEP_STUBS));
        when(filterGrid.getElement()).thenReturn(new com.vaadin.flow.dom.Element("div"));
        form = new ListForm<>(meta, filterGrid);
    }

    @Test
    void createDisabledUntilRequiredFilled() {
        form.setContextFilters(List.of(ContextFilterField.requiredAuto("code", "Код")));

        assertThat(form.getAddButton().isEnabled()).isFalse();

        form.setContextFilter("code", "A");

        assertThat(form.getAddButton().isEnabled()).isTrue();
        assertThat(form.getRequiredContextValues()).containsEntry("code", "A");

        form.clearContextFilter("code");

        assertThat(form.getAddButton().isEnabled()).isFalse();
        assertThat(form.getRequiredContextValues()).isEmpty();
    }

    @Test
    void createEnabledWithoutRequiredFields() {
        form.setContextFilters(List.of(ContextFilterField.auto("code", "Код")));

        assertThat(form.getAddButton().isEnabled()).isTrue();
    }

    @EntityMetadata(listFormTitle = "ЕИ", selectionFormTitle = "Выбор ЕИ")
    static class Unit extends org.ipro.crud.BaseEntity {
        @FieldMetadata(label = "Код", grid = @GridColumn(order = 1))
        String code;
    }
}
