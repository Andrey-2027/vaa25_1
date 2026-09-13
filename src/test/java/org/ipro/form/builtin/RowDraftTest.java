package org.ipro.form.builtin;

import org.ipro.crud.IdentifiableEntity;
import org.ipro.metadata.FieldMetadataInfo;
import org.ipro.metadata.annotation.FieldMetadata;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RowDraftTest {

    @Test
    void restoreReturnsScalarAndEntityReferenceToCapturedValues() throws Exception {
        IdentifiableEntity originalReference = mock(IdentifiableEntity.class);
        IdentifiableEntity changedReference = mock(IdentifiableEntity.class);
        when(originalReference.getId()).thenReturn(7L);

        Row row = new Row("before", originalReference);
        RowDraft<Row> draft = RowDraft.capture(row, fields());
        row.name = "after";
        row.reference = changedReference;

        draft.restore(row);

        assertThat(row.name).isEqualTo("before");
        assertThat(row.reference).isSameAs(originalReference);
    }

    /**
     * C4.0 characterization / C4.1 contract: restore не выполняет per-reference read.
     * Прежний путь хранил тип+id ссылки и перечитывал её через
     * {@code LookupService.findById} — по одному запросу на каждую ссылку строки
     * (UI-managed N+1). Теперь восстанавливается захваченный экземпляр.
     */
    @Test
    void restoreRestoresEveryEntityReferenceWithoutAnyRead() throws Exception {
        IdentifiableEntity first = mock(IdentifiableEntity.class);
        IdentifiableEntity second = mock(IdentifiableEntity.class);
        when(first.getId()).thenReturn(1L);
        when(second.getId()).thenReturn(2L);

        PairRow row = new PairRow(first, second);
        RowDraft<PairRow> draft = RowDraft.capture(row, pairFields());
        row.first = mock(IdentifiableEntity.class);
        row.second = mock(IdentifiableEntity.class);

        draft.restore(row);

        assertThat(row.first).isSameAs(first);
        assertThat(row.second).isSameAs(second);
    }

    /**
     * Прежний путь не умел восстановить ссылку без id (findById(null) → empty →
     * IllegalStateException). Теперь несохранённая ссылка восстанавливается как есть.
     */
    @Test
    void restoreKeepsUnsavedReferenceWhichHasNoId() throws Exception {
        IdentifiableEntity unsaved = mock(IdentifiableEntity.class);
        when(unsaved.getId()).thenReturn(null);

        PairRow row = new PairRow(unsaved, unsaved);
        RowDraft<PairRow> draft = RowDraft.capture(row, pairFields());
        row.first = mock(IdentifiableEntity.class);

        draft.restore(row);

        assertThat(row.first).isSameAs(unsaved);
    }

    private static List<FieldMetadataInfo> fields() throws NoSuchFieldException {
        return List.of(field("name"), field("reference"));
    }

    private static List<FieldMetadataInfo> pairFields() throws NoSuchFieldException {
        return List.of(pairField("first"), pairField("second"));
    }

    private static FieldMetadataInfo pairField(String name) throws NoSuchFieldException {
        Field field = PairRow.class.getDeclaredField(name);
        return new FieldMetadataInfo(field, field.getAnnotation(FieldMetadata.class));
    }

    private static FieldMetadataInfo field(String name) throws NoSuchFieldException {
        Field field = Row.class.getDeclaredField(name);
        return new FieldMetadataInfo(field, field.getAnnotation(FieldMetadata.class));
    }

    private static class Row {
        @FieldMetadata
        private String name;

        @FieldMetadata
        private IdentifiableEntity reference;

        private Row(String name, IdentifiableEntity reference) {
            this.name = name;
            this.reference = reference;
        }
    }

    private static class PairRow {
        @FieldMetadata
        private IdentifiableEntity first;

        @FieldMetadata
        private IdentifiableEntity second;

        private PairRow(IdentifiableEntity first, IdentifiableEntity second) {
            this.first = first;
            this.second = second;
        }
    }
}
