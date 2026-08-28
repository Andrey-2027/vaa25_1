package org.ipro.filter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Преобразует канонические строковые значения условий в тип поля. */
public final class FilterValueCodec {
    private FilterValueCodec() {
    }

    public static Object decode(String value, FilterFieldResolver.ResolvedFilterField field) {
        if (value == null) return null;
        Class<?> type = wrap(field.javaType());
        try {
            if (type == String.class || type == Object.class) return value;
            if (type == Integer.class) return Integer.valueOf(value);
            if (type == Long.class) return Long.valueOf(value);
            if (type == Short.class) return Short.valueOf(value);
            if (type == Double.class) return Double.valueOf(value);
            if (type == Float.class) return Float.valueOf(value);
            if (type == BigDecimal.class) return new BigDecimal(value);
            if (type == Boolean.class) {
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value))
                    throw new IllegalArgumentException("Ожидалось true или false");
                return Boolean.valueOf(value);
            }
            if (type == LocalDate.class) return LocalDate.parse(value);
            if (type == LocalDateTime.class) return LocalDateTime.parse(value);
            if (type == UUID.class) return UUID.fromString(value);
            if (type.isEnum()) return Enum.valueOf((Class<? extends Enum>) type.asSubclass(Enum.class), value);
            return value;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Некорректное значение фильтра для поля «"
                    + field.label() + "»: " + value, e);
        }
    }

    public static List<Object> decodeList(String value, FilterFieldResolver.ResolvedFilterField field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("IN требует значения");
        return Arrays.stream(value.split(",", -1)).map(String::trim)
                .map(item -> decode(item, field)).toList();
    }

    private static Class<?> wrap(Class<?> type) {
        if (type == null || !type.isPrimitive()) return type == null ? Object.class : type;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == boolean.class) return Boolean.class;
        if (type == short.class) return Short.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return type;
    }
}
