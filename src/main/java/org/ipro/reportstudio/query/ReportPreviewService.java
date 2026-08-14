package org.ipro.reportstudio.query;

import org.ipro.reportstudio.data.QueryField;
import org.ipro.reportstudio.data.ReportDataset;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Транзакционная обёртка выполнения JPQL-запроса отчёта (Фаза 2). Vaadin-вью
 * транзакций не открывает — предпросмотр выполняется здесь, в
 * {@code @Transactional(readOnly = true)}, поверх прошедшего guard'а.
 * Регистрируется в ReportStudioAutoConfiguration (пакет вне component-scan org.ip).
 */
public class ReportPreviewService {

    private final ReportQueryExecutor executor;

    public ReportPreviewService(ReportQueryExecutor executor) {
        this.executor = executor;
    }

    @Transactional(readOnly = true, timeout = 60)
    public ReportDataset preview(String jpql, Map<String, Object> bindings,
                                 List<QueryField> fields, int maxRows, long timeoutMs) {
        return executor.execute(jpql, bindings, fields, maxRows, timeoutMs);
    }
}