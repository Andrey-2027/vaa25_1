package org.ipro.query;

import org.ipro.report.data.ReportDataset;
import org.ipro.query.ReportContext;
import org.ipro.query.model.QueryDefinition;
import org.ipro.query.model.QuerySort;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Единый transactional-вход исполнения запроса: сборка (guard + параметры +
 * визуальный запрос) и выполнение идут в одной read-only транзакции.
 *
 * <p>Раньше граница существовала только у предпросмотра и у UDR-фасада;
 * UniDR открывал собственную. Теперь общий backend-путь один, а вызывающие
 * слои передают только нейтральное описание запроса, группы и сортировки.</p>
 */
public class ReportQueryRunService {

    private final ReportQueryAssemblyService assembly;
    private final ReportQueryExecutor executor;

    public ReportQueryRunService(ReportQueryAssemblyService assembly, ReportQueryExecutor executor) {
        this.assembly = assembly;
        this.executor = executor;
    }

    @Transactional(readOnly = true)
    public ReportQueryRun run(QueryDefinition definition, ReportContext context,
                              Map<String, Object> formValues, List<String> groupFields,
                              List<QuerySort> sorts) {
        ReportQueryAssembler assembled = assembly.assemble(definition, context, formValues,
                groupFields, sorts);
        ReportDataset dataset = executor.execute(assembled,
                definition.limits().maxRows(), definition.limits().timeoutMs());
        return new ReportQueryRun(assembled, dataset);
    }
}
