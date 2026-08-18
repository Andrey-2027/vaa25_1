package org.ip.form.builtin;

import org.ip.form.FieldFactory;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.TableSectionMetadataInfo;
import org.ip.service.TableSectionService;
import org.ipro.crud.BaseEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Baseline-тесты состояния {@link ItemTable} (PR-0.1): rows, dirty, parent.
 *
 * Фиксируют текущее поведение до PR-0.4:
 *  - новый (несохранённый) родитель → пустые строки, чистое состояние;
 *  - существующий родитель → загрузка строк из service, чистое состояние;
 *  - setParent/commit сбрасывают dirty;
 *  - getRows() — неизменяемый снимок.
 *
 * Строки отмечаются dirty через UI-диалоги добавления/изменения/удаления, поэтому для
 * проверки сброса dirty используется рефлексия (как в ItemFormLifecycleTest). PR-0.4
 * добавит applyPersistedRows — тесты этого PR живут здесь же.
 */
class ItemTableStateTest {

    private TableSectionService<TestRow, TestDocument> service;
    private ItemTable<TestRow, TestDocument> table;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        TableSectionMetadataInfo sectionMeta = mock(TableSectionMetadataInfo.class);
        when(sectionMeta.getRowClass()).thenReturn((Class) TestRow.class);
        when(sectionMeta.getGridFields()).thenReturn(List.of());
        when(sectionMeta.getFormFields()).thenReturn(List.of());

        service = mock(TableSectionService.class);

        table = new ItemTable<>(sectionMeta, mock(FieldFactory.class), service,
            mock(MetadataResolver.class), null, null, null, () -> null);
    }

    @Test
    void unsavedParentHasEmptyRowsAndCleanState() {
        table.setParent(new TestDocument());

        assertThat(table.getRows()).isEmpty();
        assertThat(table.isDirty()).isFalse();
        verify(service, never()).findByParent(any());
    }

    @Test
    void setParentLoadsRowsFromServiceAndIsClean() {
        TestRow r1 = new TestRow();
        TestRow r2 = new TestRow();
        TestDocument doc = new TestDocument();
        doc.setId(1L);
        when(service.findByParent(eq(doc), anyCollection())).thenReturn(List.of(r1, r2));

        table.setParent(doc);

        assertThat(table.getRows()).containsExactly(r1, r2);
        assertThat(table.isDirty()).isFalse();
        verify(service).findByParent(eq(doc), anyCollection());
    }

    @Test
    void setParentClearsDirtiness() throws Exception {
        markDirty();

        table.setParent(new TestDocument());

        assertThat(table.isDirty()).isFalse();
        assertThat(table.getRows()).isEmpty();
    }

    @Test
    void commitReplacesRowsOnServiceAndReloadsClean() throws Exception {
        TestRow r1 = new TestRow();
        TestRow r2 = new TestRow();
        TestDocument doc = new TestDocument();
        doc.setId(1L);
        when(service.findByParent(eq(doc), anyCollection())).thenReturn(List.of(r1, r2));
        table.setParent(doc);

        TestDocument saved = new TestDocument();
        saved.setId(5L);
        TestRow r3 = new TestRow();
        when(service.findByParent(eq(saved), anyCollection())).thenReturn(List.of(r3));

        // commit мутирует тот же список rows (clear + reload), поэтому снапшот снимаем
        // в момент вызова replaceAll, а не через verify (после commit там уже [r3])
        List<TestRow> persisted = new ArrayList<>();
        doAnswer(invocation -> {
            persisted.addAll(invocation.getArgument(1));
            return null;
        }).when(service).replaceAll(eq(saved), anyList());

        markDirty();
        table.commit(saved);

        assertThat(persisted).containsExactly(r1, r2);
        assertThat(table.getRows()).containsExactly(r3);
        assertThat(table.isDirty()).isFalse();
    }

    @Test
    void getRowsReturnsImmutableSnapshot() {
        TestDocument doc = new TestDocument();
        doc.setId(1L);
        when(service.findByParent(eq(doc), anyCollection())).thenReturn(List.of(new TestRow()));
        table.setParent(doc);

        List<TestRow> snapshot = table.getRows();

        assertThatThrownBy(() -> snapshot.add(new TestRow()))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void getRowClassReturnsRowClass() {
        assertThat(table.getRowClass()).isEqualTo(TestRow.class);
    }

    @Test
    void applyPersistedRowsReplacesRowsWithoutDatabaseCalls() {
        TestRow r1 = new TestRow();
        TestRow r2 = new TestRow();
        TestDocument doc = new TestDocument();
        doc.setId(1L);
        when(service.findByParent(eq(doc), anyCollection())).thenReturn(List.of(r1, r2));
        table.setParent(doc);

        TestDocument saved = new TestDocument();
        saved.setId(9L);
        TestRow r3 = new TestRow();
        table.applyPersistedRows(saved, List.of(r3));

        assertThat(table.getRows()).containsExactly(r3);
        assertThat(table.isDirty()).isFalse();
        // никакого повторного запроса к БД — строки пришли из use case
        verify(service, never()).findByParent(eq(saved), anyCollection());
        verify(service, never()).replaceAll(any(), anyList());
    }

    @Test
    void applyPersistedRowsClearsDirtiness() throws Exception {
        TestRow r1 = new TestRow();
        TestDocument doc = new TestDocument();
        doc.setId(1L);
        when(service.findByParent(eq(doc), anyCollection())).thenReturn(List.of(r1));
        table.setParent(doc);

        markDirty();
        table.applyPersistedRows(new TestDocument(), List.of());

        assertThat(table.isDirty()).isFalse();
        assertThat(table.getRows()).isEmpty();
    }

    private void markDirty() throws Exception {
        Field dirty = ItemTable.class.getDeclaredField("dirty");
        dirty.setAccessible(true);
        dirty.setBoolean(table, true);
    }

    static class TestRow extends BaseEntity {
    }

    static class TestDocument extends BaseEntity {
    }
}
