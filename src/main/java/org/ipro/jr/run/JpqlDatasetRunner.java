package org.ipro.jr.run;

import java.util.Map;

import org.ipro.reportstudio.data.ReportDataset;

/**
 * Порты выполнения JPQL для движка JR: реализация — {@code JpqlRunService}
 * приложения (guard → preview → RLS). Интерфейс в платформе, реализация в
 * приложении — направление зависимости соблюдено (план ReportJR-Jpql-Plan.md).
 */
@FunctionalInterface
public interface JpqlDatasetRunner {

    /**
     * Выполняет JPQL под текущим пользователем через единый конвейер.
     *
     * @param maxColumns лимит колонок вызывающего движка
     */
    org.ipro.reportstudio.data.ReportDataset run(String jpql, Map<String, Object> bindings,
                                                 int maxColumns);

    /**
     * Отображаемое значение ячейки: entity-ссылки ({@code EntityRef}) →
     * caption (иначе UI/JSON выдаёт "[object Object]"), остальное — как есть.
     */
    static Object displayValue(Object value) {
        if (value instanceof org.ipro.reportstudio.data.EntityRef ref) {
            return ref.caption() != null ? ref.caption() : String.valueOf(ref.id());
        }
        return value;
    }
}
