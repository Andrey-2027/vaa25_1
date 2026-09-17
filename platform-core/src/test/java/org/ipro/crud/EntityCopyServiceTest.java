package org.ipro.crud;

import jakarta.persistence.Column;
import jakarta.persistence.ManyToOne;
import org.ipro.metadata.TableSectionMetadataInfo;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;
import org.ipro.numbering.annotation.Numbered;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Тесты копирования сущностей (команда «Копировать»): сброс технического,
 * очистка номеров/unique, ссылки те же, источник не меняется.
 */
class EntityCopyServiceTest {

    private final EntityCopyService copy = new EntityCopyService();

    @Test
    void headerCopiedWithTechnicalReset() {
        Doc source = new Doc();
        source.setId(42L);
        source.setVersion(3L);
        source.code = "A-1";
        source.number = "100";
        source.name = "Гайка";
        Journal journal = new Journal();
        source.journal = journal;

        Doc result = copy.copyEntity(source);

        assertThat(result).isNotSameAs(source);
        assertThat(result.getId()).isNull();
        assertThat(result.getVersion()).isNull();
        assertThat(result.name).isEqualTo("Гайка");
        assertThat(result.journal).isSameAs(journal);
        // Номер и уникальный код очищены — выдадутся заново при сохранении
        assertThat(result.number).isNull();
        assertThat(result.code).isNull();
        // Источник не тронут
        assertThat(source.getId()).isEqualTo(42L);
        assertThat(source.number).isEqualTo("100");
    }

    @Test
    void rowCopiedWithoutParentAndLine() throws Exception {
        TableSectionMetadataInfo section = mock(TableSectionMetadataInfo.class);
        when(section.getParentFieldName()).thenReturn("doc");
        when(section.hasLineNumberField()).thenReturn(true);
        when(section.getLineNumberFieldName()).thenReturn("lineNumber");
        Row source = new Row();
        source.setId(7L);
        source.data = "x";
        Doc newParent = new Doc();

        Row result = copy.copyRow(source, section, newParent);

        assertThat(result).isNotSameAs(source);
        assertThat(result.getId()).isNull();
        assertThat(result.data).isEqualTo("x");
    }

    @Test
    void nullSourceGivesNull() {
        assertThat(copy.copyEntity((Doc) null)).isNull();
    }

    static class Journal extends BaseEntity {
    }

    static class Doc extends BaseEntity {
        @FieldMetadata(label = "Код", grid = @GridColumn(order = 1))
        @Column(unique = true)
        String code;
        @FieldMetadata(label = "Номер", grid = @GridColumn(order = 2))
        @Numbered
        String number;
        @FieldMetadata(label = "Имя", grid = @GridColumn(order = 3))
        String name;
        @ManyToOne
        @FieldMetadata(label = "Журнал")
        Journal journal;
    }

    static class Row extends BaseEntity {
        Doc doc;
        Integer lineNumber;
        @FieldMetadata(label = "Данные")
        String data;
    }
}
