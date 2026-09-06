package org.ipro.form;

import org.ipro.crud.BaseEntity;
import org.ipro.crud.EntityCopyService;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;
import org.ipro.metadata.annotation.TableSectionMetadata;
import org.ipro.metadata.annotation.TableSections;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Тесты копирования строк табчастей: перепривязка на нового родителя,
 * перенумерация с 1, пустые секции пропускаются.
 */
class CopyRowsForTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void rowsCopiedLinkedAndRenumbered() {
        MetadataResolver metadataResolver = new MetadataResolver();
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        RowService rowService = mock(RowService.class);
        when(applicationContext.getBean(RowService.class)).thenReturn(rowService);

        Doc source = new Doc();
        source.setId(1L);
        Row first = new Row();
        first.setId(11L);
        first.data = "a";
        Row second = new Row();
        second.setId(12L);
        second.data = "b";
        when(rowService.findByParent(any())).thenReturn(List.of(first, second));

        TableSectionFactory factory = new TableSectionFactory(metadataResolver, mock(FieldFactory.class),
            applicationContext, mock(org.springframework.beans.factory.ObjectProvider.class), List.of());
        Doc target = new Doc();

        Map<Class<?>, List<?>> copied =
            factory.copyRowsFor(Doc.class, source, target, new EntityCopyService());

        assertThat(copied).containsOnlyKeys(Row.class);
        List<?> rows = copied.get(Row.class);
        assertThat(rows).hasSize(2);
        Row firstCopy = (Row) rows.get(0);
        Row secondCopy = (Row) rows.get(1);
        assertThat(firstCopy).isNotSameAs(first);
        assertThat(firstCopy.getId()).isNull();
        assertThat(firstCopy.data).isEqualTo("a");
        assertThat(firstCopy.parent).isSameAs(target);
        assertThat(firstCopy.lineNumber).isEqualTo(1);
        assertThat(secondCopy.lineNumber).isEqualTo(2);
        // Источник не тронут
        assertThat(first.getId()).isEqualTo(11L);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void emptySectionsSkipped() {
        MetadataResolver metadataResolver = new MetadataResolver();
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        RowService rowService = mock(RowService.class);
        when(applicationContext.getBean(RowService.class)).thenReturn(rowService);
        when(rowService.findByParent(any())).thenReturn(List.of());

        TableSectionFactory factory = new TableSectionFactory(metadataResolver, mock(FieldFactory.class),
            applicationContext, mock(org.springframework.beans.factory.ObjectProvider.class), List.of());

        assertThat(factory.copyRowsFor(Doc.class, new Doc(), new Doc(), new EntityCopyService()))
            .isEmpty();
    }

    interface RowService extends org.ipro.crud.TableSectionService<Row, Doc> {
    }

    @TableSections({Row.class})
    static class Doc extends BaseEntity {
    }

    @TableSectionMetadata(parentEntity = Doc.class, parentField = "parent",
        lineNumberField = "lineNumber", serviceClass = RowService.class)
    static class Row extends BaseEntity {
        Doc parent;
        Integer lineNumber;
        @FieldMetadata(label = "Данные", grid = @GridColumn(order = 1))
        String data;
    }
}
