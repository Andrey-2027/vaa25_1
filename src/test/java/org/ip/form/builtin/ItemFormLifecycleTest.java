package org.ip.form.builtin;

import org.ip.form.FieldFactory;
import org.ip.metadata.EntityMetadataInfo;
import org.ipro.crud.BaseEntity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<ItemTable<?, TestDocument>> tableSections(ItemForm<TestDocument> form) throws Exception {
        Field field = ItemForm.class.getDeclaredField("tableSections");
        field.setAccessible(true);
        return (List) field.get(form);
    }

    public static class TestDocument extends BaseEntity {
    }
}
