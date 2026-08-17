package org.ip.form.builtin;

import com.vaadin.flow.component.textfield.TextField;
import org.ip.form.FieldFactory;
import org.ip.form.FormBinding;
import org.ip.form.FormBindingRegistry;
import org.ip.metadata.EntityMetadataInfo;
import org.ip.metadata.FieldMetadataInfo;
import org.ipro.crud.BaseEntity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ItemFormLifecycleTest {

    @Test
    void initializeNewEntityRetainsTheUnsavedEntityInFormState() {
        EntityMetadataInfo metadata = mock(EntityMetadataInfo.class);
        doReturn(TestDocument.class).when(metadata).getEntityClass();
        when(metadata.getFormFields()).thenReturn(List.of());

        ItemForm<TestDocument> form = new ItemForm<>(metadata, mock(FieldFactory.class));

        TestDocument created = form.initializeNewEntity();

        assertThat(created).isSameAs(form.peekEntity());
        assertThat(created).isSameAs(form.getEntity());
        assertThat(created.getId()).isNull();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void initializeNewEntityAssignsTheUnsavedParentToEveryTableSection() throws Exception {
        EntityMetadataInfo metadata = mock(EntityMetadataInfo.class);
        doReturn(TestDocument.class).when(metadata).getEntityClass();
        when(metadata.getFormFields()).thenReturn(List.of());

        ItemForm<TestDocument> form = new ItemForm<>(metadata, mock(FieldFactory.class));
        ItemTable table = mock(ItemTable.class);
        tableSections(form).add(table);

        TestDocument created = form.initializeNewEntity();

        verify(table).setParent(created);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void tableSectionFindsSectionByExactRowClass() throws Exception {
        ItemForm<TestDocument> form = formWithMetadata();
        ItemTable<TestRow, TestDocument> table = mock(ItemTable.class);
        when(table.getRowClass()).thenReturn(TestRow.class);
        tableSections(form).add(table);

        assertThat(form.tableSection(TestRow.class)).isSameAs(table);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void tableSectionThrowsWhenRowClassAbsent() throws Exception {
        ItemForm<TestDocument> form = formWithMetadata();

        assertThatThrownBy(() -> form.tableSection(TestRow.class))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("TestRow");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void tableSectionThrowsWhenRowClassAmbiguous() throws Exception {
        ItemForm<TestDocument> form = formWithMetadata();
        ItemTable table1 = mock(ItemTable.class);
        ItemTable table2 = mock(ItemTable.class);
        when(table1.getRowClass()).thenReturn(TestRow.class);
        when(table2.getRowClass()).thenReturn(TestRow.class);
        tableSections(form).add(table1);
        tableSections(form).add(table2);

        assertThatThrownBy(() -> form.tableSection(TestRow.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("несколько");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void applyPersistedEntitySetsSavedEntityWithoutReloadingTables() throws Exception {
        ItemForm<TestDocument> form = formWithMetadata();
        ItemTable table = mock(ItemTable.class);
        tableSections(form).add(table);

        TestDocument saved = new TestDocument();
        saved.setId(42L);
        form.applyPersistedEntity(saved);

        assertThat(form.peekEntity()).isSameAs(saved);
        // строки придут через applyPersistedRows — таблицы не перечитываются из БД
        verify(table, never()).setParent(any());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void applyPersistedEntityPopulatesFieldsFromSavedEntity() throws Exception {
        ItemForm<TestDocument> form = formWithMetadata();
        TextField idField = new TextField();
        registry(form).add(new FormBinding(
            fieldMetadata("id"),
            idField,
            e -> String.valueOf(((TestDocument) e).getId()),
            (e, v) -> {
            },
            idField::getValue,
            v -> idField.setValue((String) v),
            v -> v == null || v.toString().isEmpty(),
            idField::setReadOnly));

        TestDocument saved = new TestDocument();
        saved.setId(42L);
        form.applyPersistedEntity(saved);

        assertThat(idField.getValue()).isEqualTo("42");
    }

    private static ItemForm<TestDocument> formWithMetadata() {
        EntityMetadataInfo metadata = mock(EntityMetadataInfo.class);
        doReturn(TestDocument.class).when(metadata).getEntityClass();
        when(metadata.getFormFields()).thenReturn(List.of());
        return new ItemForm<>(metadata, mock(FieldFactory.class));
    }

    private static FormBindingRegistry registry(ItemForm<TestDocument> form) throws Exception {
        Field field = ItemForm.class.getDeclaredField("registry");
        field.setAccessible(true);
        return (FormBindingRegistry) field.get(form);
    }

    private static FieldMetadataInfo fieldMetadata(String name) {
        FieldMetadataInfo meta = mock(FieldMetadataInfo.class);
        when(meta.getName()).thenReturn(name);
        when(meta.getLabel()).thenReturn(name);
        when(meta.isRequired()).thenReturn(false);
        return meta;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<ItemTable<?, TestDocument>> tableSections(ItemForm<TestDocument> form) throws Exception {
        Field field = ItemForm.class.getDeclaredField("tableSections");
        field.setAccessible(true);
        return (List) field.get(form);
    }

    public static class TestDocument extends BaseEntity {
    }

    public static class TestRow extends BaseEntity {
    }
}
