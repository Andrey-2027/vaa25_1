package org.ipro.form.registry;

import java.util.List;
import java.util.Objects;

/**
 * Именованный набор колонок Формы Выбора для одной сущности — data-вариант выбора.
 *
 * В отличие от {@link FormFactory} (полностью кастомный диалог), здесь только данные:
 * список колонок и заголовок. Сборку диалога делает {@code SelectionFormAssembler}
 * тем же кодом, что и generic-путь, поэтому автокомплит ({@code FieldFactory}) и диалог
 * заведомо не расходятся — оба читают один и тот же набор.
 *
 * @param columns имена Java-полей целевой сущности (поддерживается путь через точку)
 * @param title заголовок диалога; пусто/null — default-заголовок из метаданных
 */
public record SelectionColumnsDef(
    List<String> columns,
    String title
) {
    public SelectionColumnsDef {
        Objects.requireNonNull(columns, "columns cannot be null");
        columns = List.copyOf(columns);
    }

    public static SelectionColumnsDef of(List<String> columns, String title) {
        return new SelectionColumnsDef(columns, title);
    }

    public static SelectionColumnsDef of(String... columns) {
        return new SelectionColumnsDef(List.of(columns), null);
    }
}
