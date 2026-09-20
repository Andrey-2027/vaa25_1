package org.ipro.form;

import org.ipro.fetch.instance.InstanceNameBridge;
import org.ipro.metadata.annotation.FieldType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;

/**
 * Рендер значения поля сущности в строку для отображения в гриде.
 *
 * Используется ListForm для отображения значений разных типов:
 *   - String → "Код" (как есть)
 *   - LocalDate → "15.07.2026"
 *   - BigDecimal → "123.45" (без экспоненциальной записи)
 *   - Boolean → "Да" / "Нет"
 *   - @ManyToOne → displayName связанной сущности
 *
 * Реализации по умолчанию (static factory methods) покрывают стандартные случаи.
 * Принимает уже извлечённое значение (не сущность+поле) — это позволяет применять один и тот же
 * рендер и к обычному полю (FieldMetadataInfo.getValue(entity)), и к значению, полученному по
 * пути через точку (ColumnPath.getValue(entity)), не зная деталей извлечения.
 */
@FunctionalInterface
public interface FieldRenderer extends Function<Object, String> {

    @Override
    String apply(Object value);

    /**
     * Текстовый рендер: toString() или пустая строка для null.
     * Подходит для String, Integer, Long и других типов, у которых адекватный toString.
     */
    static FieldRenderer text() {
        return value -> value == null ? "" : value.toString();
    }

    /**
     * Рендер LocalDate в формате "dd.MM.yyyy".
     */
    static FieldRenderer date() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy");
        return value -> {
            if (value == null) return "";
            if (value instanceof LocalDate date) return date.format(formatter);
            return value.toString();
        };
    }

    /**
     * Рендер LocalDateTime в формате "dd.MM.yyyy HH:mm".
     */
    static FieldRenderer dateTime() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
        return value -> {
            if (value == null) return "";
            if (value instanceof java.time.LocalDateTime dt) return dt.format(formatter);
            return value.toString();
        };
    }

    /**
     * Рендер BigDecimal/Double/Float как plain string (без экспоненты).
     */
    static FieldRenderer decimal() {
        return value -> {
            if (value == null) return "";
            if (value instanceof BigDecimal bd) return bd.toPlainString();
            if (value instanceof Double d) {
                if (d.isNaN() || d.isInfinite()) return "";
                return BigDecimal.valueOf(d).toPlainString();
            }
            if (value instanceof Float f) {
                if (f.isNaN() || f.isInfinite()) return "";
                return BigDecimal.valueOf(f).toPlainString();
            }
            return value.toString();
        };
    }

    /**
     * Рендер Boolean как "Да" / "Нет".
     */
    static FieldRenderer boolYesNo() {
        return value -> {
            if (value == null) return "";
            return Boolean.TRUE.equals(value) ? "Да" : "Нет";
        };
    }

    /**
     * Рендер Enum: использует name() значения.
     */
    static FieldRenderer enumValue() {
        return value -> {
            if (value == null) return "";
            if (value instanceof Enum<?> e) return e.name();
            return value.toString();
        };
    }

    /**
     * Рендер связанной сущности (@ManyToOne, @OneToOne) через единый источник имени:
     * мигрированные на {@code @InstanceName} сущности берутся из резолвера, остальные —
     * {@code HasDisplayName}, иначе {@code toString()}. Ни один канал отображения не должен
     * собирать эту лестницу сам (ADX-09).
     */
    static FieldRenderer entityReference() {
        return value -> InstanceNameBridge.displayName(value);
    }

    /**
     * Рендер даты-времени с секундами ("dd.MM.yyyy HH:mm:ss").
     * Принимает любой TemporalAccessor (LocalDateTime, ZonedDateTime, ...);
     * Instant без зоны форматировать нельзя — передавайте с atZone(), иначе toString().
     * Используется журналом аудита в ItemForm (там время — Instant из БД).
     */
    static FieldRenderer dateTimeWithSeconds() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
        return value -> {
            if (value == null) return "";
            if (value instanceof java.time.temporal.TemporalAccessor temporal) {
                try {
                    return formatter.format(temporal);
                } catch (java.time.DateTimeException notFormattable) {
                    return value.toString();
                }
            }
            return value.toString();
        };
    }

    /**
     * Единая точка выбора рендера по FieldType. Используется и ListForm (колонки грида
     * сущностей), и ItemTable (колонки грида строк табличной части) — чтобы оба места
     * рендерили значения одинаково и не расходились при развитии типов полей.
     */
    static FieldRenderer forType(FieldType type) {
        return switch (type) {
            case DATE -> date();
            case DATETIME -> dateTime();
            case DECIMAL -> decimal();
            case BOOLEAN -> boolYesNo();
            case ENUM -> enumValue();
            case ENTITY_REFERENCE -> entityReference();
            default -> text();
        };
    }
}
