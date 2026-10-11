package org.ipro.metadata;

import org.ipro.metadata.annotation.FieldMetadata;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Два разных вопроса о поле: фильтруется ли оно вовсе ({@code filter}) и показывать ли встроенную
 * строку быстрого фильтра под заголовком колонки ({@code inlineFilter}). Регистр сведений снимает
 * только второй — широкий диапазон даты-времени мешает списку, а условие по периоду обязано
 * остаться разрешённым.
 */
class FieldMetadataInlineFilterTest {

    static class Entity {
        @FieldMetadata(label = "Период", inlineFilter = false)
        private LocalDateTime period;

        @FieldMetadata(label = "Наименование", filter = false, inlineFilter = false)
        private String name;

        @FieldMetadata(label = "Цена")
        private BigDecimal price;
    }

    @Test
    void inlineFilterIsIndependentOfWhetherTheFieldIsFilterable() {
        FieldMetadataInfo period = info("period");

        assertThat(period.isFilterEnabled()).isTrue();
        assertThat(period.isInlineFilterEnabled()).isFalse();
    }

    @Test
    void bothDeclarationsCanBeOffTogether() {
        FieldMetadataInfo name = info("name");

        assertThat(name.isFilterEnabled()).isFalse();
        assertThat(name.isInlineFilterEnabled()).isFalse();
    }

    @Test
    void defaultIsAnInlineFilter() {
        FieldMetadataInfo price = info("price");

        assertThat(price.isFilterEnabled()).isTrue();
        assertThat(price.isInlineFilterEnabled()).isTrue();
    }

    private static FieldMetadataInfo info(String fieldName) {
        try {
            Field field = Entity.class.getDeclaredField(fieldName);
            return new FieldMetadataInfo(field, field.getAnnotation(FieldMetadata.class));
        } catch (NoSuchFieldException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
