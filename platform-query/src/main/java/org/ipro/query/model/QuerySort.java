package org.ipro.query.model;

import java.util.Objects;

/**
 * Требование к порядку строк, не зависящее от печатного макета. Отчётный
 * адаптер вычисляет такие требования из своих групп и сортировок и передаёт
 * их query backend как обычные сортировки.
 */
public record QuerySort(String columnName, boolean descending) {

    public QuerySort {
        Objects.requireNonNull(columnName, "columnName");
        if (columnName.isBlank()) {
            throw new IllegalArgumentException("QuerySort: имя колонки обязательно");
        }
    }
}
