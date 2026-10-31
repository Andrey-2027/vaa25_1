package org.ipro.consumer;

import org.ipro.query.ui.result.QueryResultTable;

/**
 * Негативный потребитель M1 для UI: таблица результата запроса без отчётных
 * движков. Удаление {@code platform-query-vaadin} ломает компиляцию; появление
 * Jasper/DynamicReports/reportui в classpath ловится скриптом.
 */
public final class UiConsumer {

    public QueryResultTable resultTable() {
        return new QueryResultTable();
    }
}
