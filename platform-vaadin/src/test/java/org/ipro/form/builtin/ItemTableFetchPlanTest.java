package org.ipro.form.builtin;

import org.ipro.crud.BaseEntity;
import org.ipro.crud.LookupService;
import org.ipro.crud.TableSectionService;
import org.ipro.metadata.ColumnPath;
import org.ipro.form.FieldFactory;
import org.ipro.form.registry.FormResolver;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.TableSectionMetadataInfo;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.metadata.annotation.GridColumn;
import org.ipro.metadata.annotation.TableSectionMetadata;
import org.ipro.metadata.annotation.TableSections;
import org.junit.jupiter.api.Test;

import jakarta.persistence.ManyToOne;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ItemTableFetchPlanTest {

    @Test
    void refreshesWholeSectionOnceAndPreservesUnsavedRowEdits() throws Exception {
        MetadataResolver metadataResolver = new MetadataResolver();
        TableSectionMetadataInfo descriptor = metadataResolver.resolveTableSections(Document.class)
            .get(0);
        TableSectionService<Row, Document> service = mock(TableSectionService.class);
        LookupService lookupService = mock(LookupService.class);

        Document parent = new Document();
        parent.setId(100L);
        Target currentReference = new Target();
        currentReference.setId(9L);
        Row editedRow = new Row(currentReference, "unsaved value");
        Target currentDynamicReference = new Target();
        currentDynamicReference.setId(10L);
        editedRow.dynamicReference = currentDynamicReference;
        editedRow.setId(1L);

        Target hydratedReference = new Target();
        hydratedReference.setId(9L);
        Row databaseRow = new Row(hydratedReference, "persisted value");
        Target hydratedDynamicReference = new Target();
        hydratedDynamicReference.setId(10L);
        databaseRow.dynamicReference = hydratedDynamicReference;
        databaseRow.setId(1L);
        doReturn(List.of(editedRow), List.of(databaseRow))
            .when(service).findByParent(any(Document.class), anyCollection());

        ItemTable<Row, Document> table = new ItemTable<>(descriptor, mock(FieldFactory.class),
            service, metadataResolver, null, null, lookupService, () -> mock(FormResolver.class));
        var activeColumns = ItemTable.class.getDeclaredField("activeColumns");
        activeColumns.setAccessible(true);
        activeColumns.set(table, List.of(ColumnPath.resolve(Row.class, "dynamicReference.name")));
        table.setParent(parent);

        Method refresh = ItemTable.class.getDeclaredMethod("hydrateAllRows");
        refresh.setAccessible(true);
        refresh.invoke(table);

        Row actual = table.getRows().get(0);
        assertThat(actual.dynamicReference).isSameAs(hydratedDynamicReference);
        assertThat(actual.getNote()).isEqualTo("unsaved value");
        verify(service, times(2)).findByParent(any(Document.class), anyCollection());
        verify(lookupService, never()).findById(any(), any(), anyCollection());
    }

    @TableSections({Row.class})
    static class Document extends BaseEntity {
    }

    @TableSectionMetadata(parentEntity = Document.class, parentField = "document")
    static class Row extends BaseEntity {

        private Document document;

        @ManyToOne
        private Target dynamicReference;

        @FieldMetadata(label = "Ссылка", type = FieldType.ENTITY_REFERENCE,
            grid = @GridColumn(order = 1))
        private Target reference;

        @FieldMetadata(label = "Комментарий")
        private String note;

        Row(Target reference, String note) {
            this.reference = reference;
            this.note = note;
        }

        public Target getReference() {
            return reference;
        }

        public String getNote() {
            return note;
        }
    }

    @EntityMetadata(listFormTitle = "Target", itemFormTitle = "Target")
    static class Target extends BaseEntity {

        private String name;
    }
}
