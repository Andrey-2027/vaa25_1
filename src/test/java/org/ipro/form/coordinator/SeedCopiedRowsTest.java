package org.ipro.form.coordinator;

import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builtin.ItemTable;
import org.ipro.crud.BaseEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Тесты сидинга скопированных строк в новую карточку: только непустые секции,
 * отсутствующие пропускаются, засеянные помечаются изменёнными.
 */
class SeedCopiedRowsTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void seedsNonEmptySectionsAndMarksDirty() {
        ItemForm form = mock(ItemForm.class);
        ItemTable table = mock(ItemTable.class);
        Row parent = new Row();
        List<Object> rows = List.of(new Object());
        org.mockito.Mockito.doReturn(table).when(form).tableSection(Row.class);
        org.mockito.Mockito.doReturn(parent).when(form).peekEntity();

        FormCoordinator.seedCopiedRows(form, Map.of("presetRows", Map.of(Row.class, rows)));

        verify(table).applyPersistedRows(parent, rows);
        verify(table).markDirty();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void skipsEmptyAndMissingSections() {
        ItemForm form = mock(ItemForm.class);
        org.mockito.Mockito.doThrow(new IllegalArgumentException("нет секции"))
            .when(form).tableSection(any(Class.class));

        FormCoordinator.seedCopiedRows(form,
            Map.of("presetRows", Map.of(Row.class, List.of(), String.class, List.of(new Object()))));

        verify(form, never()).peekEntity();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void noPresetRowsIsNoOp() {
        ItemForm form = mock(ItemForm.class);

        FormCoordinator.seedCopiedRows(form, Map.of());
        FormCoordinator.seedCopiedRows(form, null);

        verify(form, never()).tableSection(any(Class.class));
    }

    static class Row extends BaseEntity {
    }
}
