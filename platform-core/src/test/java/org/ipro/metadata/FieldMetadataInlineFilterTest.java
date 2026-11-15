package org.ipro.metadata;

import org.ipro.metadata.annotation.FieldMetadata;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Показывать ли встроенную строку быстрого фильтра под заголовком колонки
 * ({@code inlineFilter}). Регистр сведений снимает её у периода — широкий диапазон
 * даты-времени мешает списку, а условие по периоду обязано остаться разрешённым. Отдельного
 * признака «поле не фильтруется вовсе» нет: отбор по колонке доступен всегда (AF1.3).
 */
class FieldMetadataInlineFilterTest {

    static class Entity {
        @FieldMetadata(label = "Период", inlineFilter = false)
        private LocalDateTime period;

        @FieldMetadata(label = "Цена")
        private BigDecimal price;
    }

    @Test
    void periodDeclaresNoInlineFilterControl() {
        assertThat(info("period").isInlineFilterEnabled()).isFalse();
    }

    @Test
    void defaultIsAnInlineFilter() {
        assertThat(info("price").isInlineFilterEnabled()).isTrue();
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
