package org.ipro.filter;

/** Маркер параметризованного значения фильтра. Хранится канонически как :name. */
public record FilterParameterRef(String name) {
    public FilterParameterRef {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Некорректное имя параметра фильтра: " + name);
        }
    }
    public static FilterParameterRef parse(String value) {
        if (value == null || !value.startsWith(":") || value.length() == 1) return null;
        return new FilterParameterRef(value.substring(1));
    }
}
