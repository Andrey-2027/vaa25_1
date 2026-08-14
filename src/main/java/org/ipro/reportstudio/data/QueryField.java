package org.ipro.reportstudio.data;

import java.util.Objects;

/**
 * Поле результата JPQL-запроса (schema отчёта, Фаза 2). Иммутабельный слот
 * колонки {@link ReportDataset}: name — alias из SELECT (имя для ReportField),
 * expression — исходный путь выражения ("d.journal.code" или "" для
 * вычисляемых/функциональных колонок), javaType — тип значения, caption —
 * человекочитаемый заголовок (из @FieldMetadata цепочки путей, иначе alias),
 * sortable/groupable/aggregatable — подсказки конструктору (Фаза 5).
 */
public record QueryField(
        String name,
        String expression,
        Class<?> javaType,
        String caption,
        boolean sortable,
        boolean groupable,
        boolean aggregatable) {

    public QueryField {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("QueryField: имя (alias) обязательно");
        }
        expression = expression == null ? "" : expression;
        javaType = javaType == null ? Object.class : javaType;
        caption = caption == null || caption.isBlank() ? name : caption;
    }

    public static QueryField scalar(String name, Class<?> javaType) {
        return new QueryField(name, "", javaType != null ? javaType : Object.class, name,
            true, false, isNumber(javaType));
    }

    public static boolean isNumber(Class<?> javaType) {
        return javaType != null && (Number.class.isAssignableFrom(javaType)
            || javaType == int.class || javaType == long.class
            || javaType == short.class || javaType == byte.class
            || javaType == double.class || javaType == float.class);
    }

    @Override
    public String toString() {
        return "QueryField{" + name + ": " + (javaType != null ? javaType.getSimpleName() : "?")
            + (caption != null && !caption.equals(name) ? ", \"" + caption + "\"" : "") + "}";
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof QueryField other)) {
            return false;
        }
        return name.equals(other.name);
    }
}