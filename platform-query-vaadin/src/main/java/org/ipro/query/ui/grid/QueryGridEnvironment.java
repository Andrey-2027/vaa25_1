package org.ipro.query.ui.grid;

import org.ipro.query.ui.selection.EntitySelection;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Всё, что ядру просмотра нужно извне. Состав намеренно не включает виды и команды над объектом:
 * ядро собирается без них, а ими пользуются оболочки (список, UTR).
 *
 * @param selection порт выбора ссылки для полей параметров; может быть {@code null}: поля-ссылки
 *                  тогда не показываются
 * @param user      имя пользователя для контекста запуска
 */
public record QueryGridEnvironment(QueryProjectionSource projections, QueryReferenceLabels labels,
                                   EntitySelection selection, QueryTaskRunner tasks, QueryRunHistory history,
                                   Supplier<String> user) {
    public QueryGridEnvironment {
        Objects.requireNonNull(projections, "projections");
        Objects.requireNonNull(labels, "labels");
        Objects.requireNonNull(tasks, "tasks");
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(user, "user");
    }
}
