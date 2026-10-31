package org.ipro.query;

import org.ipro.report.data.ReportDataset;

/**
 * Снимок результата запроса для экрана: до {@code limit} строк и признак того,
 * что сервер вернул больше строк, чем показано.
 *
 * <p>Ограничение нельзя выводить из {@code dataset.rowCount() == limit}: ровно
 * N строк — это и полный результат, и усечённый. Поэтому сервис запрашивает
 * N+1 строку и запоминает факт усечения отдельно, а не угадывает его по числу
 * строк.</p>
 */
public record QuerySnapshot(ReportDataset dataset, boolean truncated, int limit) {

    public QuerySnapshot {
        if (dataset == null) {
            throw new IllegalArgumentException("QuerySnapshot: dataset обязателен");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("QuerySnapshot: limit должен быть положительным");
        }
        if (truncated && dataset.rowCount() != limit) {
            throw new IllegalArgumentException("QuerySnapshot: усечённый снимок должен содержать ровно "
                    + limit + " строк, а содержит " + dataset.rowCount());
        }
    }

    public int rowCount() {
        return dataset.rowCount();
    }
}
