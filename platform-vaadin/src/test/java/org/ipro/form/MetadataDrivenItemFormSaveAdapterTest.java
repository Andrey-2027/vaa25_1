package org.ipro.form;

import com.vaadin.flow.dom.Element;
import org.ipro.crud.BaseEntity;
import org.ipro.crud.MetadataDrivenAggregateSaveService;
import org.ipro.events.EventSource;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builtin.ItemTable;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.TableSectionMetadataInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetadataDrivenItemFormSaveAdapterTest {
    public static class Document extends BaseEntity { }
    public static class Row extends BaseEntity { }
    public static class OtherRow extends BaseEntity { }
    static class StringRow implements IdentifiableEntity<String> {
        public String getId() { return null; }
        public void setId(String id) { }
    }

    @Test
    void harvestPreservesAttachedEmptyAndOmitsAbsentSections() {
        ItemForm<Document> form = form();
        form.addTableSection("rows", table(List.of()));
        MetadataDrivenItemFormSaveAdapter adapter = adapter(mock(MetadataDrivenAggregateSaveService.class),
            descriptor(Row.class), descriptor(OtherRow.class));
        List<MetadataDrivenAggregateSaveService.SectionInput> inputs = adapter.harvest(form);
        assertThat(inputs).hasSize(1);
        assertThat(inputs.getFirst().rowType()).isEqualTo(Row.class);
        assertThat(inputs.getFirst().rows()).isEmpty();
    }

    @Test
    void saveHarvestsTypedRowsAndAppliesReturnedLongIdentity() {
        ItemForm<Document> form = form();
        Document input = new Document();
        form.setEntity(input);
        Row row = new Row();
        ItemTable<Row, Document> table = table(List.of(row));
        form.addTableSection("rows", table);
        Document saved = new Document();
        saved.setId(42L);
        Row persisted = new Row();
        persisted.setId(73L);
        MetadataDrivenAggregateSaveService service = mock(MetadataDrivenAggregateSaveService.class);
        when(service.save(eq(input), any(), eq(EventSource.UI))).thenReturn(
            new MetadataDrivenAggregateSaveService.AggregateSaveResult<>(saved,
                List.of(new MetadataDrivenAggregateSaveService.SectionResult(Row.class, List.of(persisted)))));
        FormSaveResult<Document> result = adapter(service, descriptor(Row.class)).save(form);
        assertThat(result).isInstanceOf(FormSaveResult.Success.class);
        assertThat(((FormSaveResult.Success<Document>) result).saved()).isSameAs(saved);
        assertThat(form.peekEntity()).isSameAs(saved);
        verify(table).applyPersistedRows(saved, List.of(persisted));
    }

    @Test
    void rejectsDeclaredNonLongRowIdentityBeforeCallingSave() {
        MetadataDrivenAggregateSaveService service = mock(MetadataDrivenAggregateSaveService.class);
        assertThatThrownBy(() -> adapter(service, descriptor(StringRow.class)).harvest(form()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("IdentifiableEntity<Long>");
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    private static ItemForm<Document> form() {
        return new ItemForm<>(Document.class, List.of(), mock(FieldFactory.class));
    }

    private static TableSectionMetadataInfo descriptor(Class<?> rowType) {
        TableSectionMetadataInfo descriptor = mock(TableSectionMetadataInfo.class);
        doReturn(rowType).when(descriptor).getRowClass();
        return descriptor;
    }

    private static MetadataDrivenItemFormSaveAdapter adapter(MetadataDrivenAggregateSaveService service,
            TableSectionMetadataInfo... descriptors) {
        SectionMetadataRegistry registry = mock(SectionMetadataRegistry.class);
        when(registry.forOwner(Document.class)).thenReturn(List.of(descriptors));
        return new MetadataDrivenItemFormSaveAdapter(registry, service);
    }

    @SuppressWarnings("unchecked") // Mockito's class token cannot represent ItemTable<Row, Document>.
    private static ItemTable<Row, Document> table(List<Row> rows) {
        ItemTable<Row, Document> table = mock(ItemTable.class);
        when(table.getElement()).thenReturn(new Element("div"));
        when(table.getRowClass()).thenReturn(Row.class);
        when(table.getRows()).thenReturn(rows);
        return table;
    }
}
