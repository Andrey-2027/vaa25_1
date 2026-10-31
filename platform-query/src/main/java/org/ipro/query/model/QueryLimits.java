package org.ipro.query.model;

/**
 * Ограничения выполнения запроса. Это политика запуска, а не описание самого
 * запроса; отчётный адаптер переносит сюда значения своего шаблона.
 */
public record QueryLimits(int maxRows, int timeoutMs) {

    public static final int DEFAULT_MAX_ROWS = 5000;
    public static final int DEFAULT_TIMEOUT_MS = 30_000;
    public static final QueryLimits DEFAULT = new QueryLimits(DEFAULT_MAX_ROWS, DEFAULT_TIMEOUT_MS);

    public QueryLimits {
        if (maxRows < 1) {
            maxRows = DEFAULT_MAX_ROWS;
        }
        if (timeoutMs < 1) {
            timeoutMs = DEFAULT_TIMEOUT_MS;
        }
    }
}
