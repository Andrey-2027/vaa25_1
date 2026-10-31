package org.ipro.consumer;

import java.util.List;

import org.ipro.query.model.QueryDefinition;
import org.ipro.query.model.QueryLimits;
import org.ipro.query.model.QuerySource;
import org.ipro.report.data.QueryField;
import org.ipro.report.data.ReportDataset;
import org.ipro.report.data.ReportRow;

/**
 * Негативный потребитель M1: только query backend и нейтральный датасет.
 *
 * <p>Если убрать {@code platform-query}, класс не компилируется. Vaadin, Jasper,
 * DynamicReports и отчётный каталог здесь не нужны — проверяется списком
 * зависимостей в {@code scripts/check-query-consumers.ps1}, а не глазами.</p>
 */
public final class BackendConsumer {

    public ReportDataset emptyResult() {
        QueryDefinition definition = new QueryDefinition(
                "select e.id from DemoEntity e",
                QuerySource.MANUAL,
                null,
                null,
                List.of(),
                QueryLimits.DEFAULT);
        if (!definition.parameters().isEmpty()) {
            throw new IllegalStateException("демо-запрос не объявляет параметров");
        }
        return new ReportDataset(new QueryField[0], new ReportRow[0]);
    }
}
