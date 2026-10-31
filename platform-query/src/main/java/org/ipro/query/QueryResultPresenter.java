package org.ipro.query;

import org.ipro.report.data.QueryField;
import org.ipro.report.data.ReportDataset;

import java.util.List;

/**
 * Порт подписи ссылок в результате запроса.
 *
 * <p>Backend запросов не знает, откуда берутся подписи: presentation-сервис,
 * реестр типов регистратора и права чтения принадлежат приложению. Реализация
 * подключается на границе приложения; сам executor остаётся нейтральным и
 * возвращает физическую проекцию, если порт не подключён.</p>
 */
public interface QueryResultPresenter {

    /**
     * Заменяет физические колонки ссылок ({@code idColumn}/{@code typeColumn})
     * подписями и сохраняет идентичность ссылки в {@link ReportDataset.ReferenceColumn}.
     *
     * @param plan     описание презентационной проекции из сборки запроса
     * @param fields   запрошенная схема результата
     * @param physical результат физического запроса (до подписи)
     */
    ReportDataset present(QueryPresentationPlan plan, List<QueryField> fields, ReportDataset physical);
}
