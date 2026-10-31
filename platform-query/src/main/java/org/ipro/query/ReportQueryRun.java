package org.ipro.query;

import org.ipro.report.data.ReportDataset;

import java.util.Objects;

/**
 * Результат исполнения запроса: подготовленный запрос и его данные.
 *
 * <p>{@link #bindings()} — фактически связанные значения; по ним строится
 * идентичность артефакта запуска, поэтому отдельный резолв параметров
 * вызывающему не нужен.</p>
 */
public record ReportQueryRun(ReportQueryAssembler assembled, ReportDataset dataset) {

    public ReportQueryRun {
        Objects.requireNonNull(assembled, "assembled");
        Objects.requireNonNull(dataset, "dataset");
    }

    public java.util.Map<String, Object> bindings() {
        return assembled.bindings();
    }
}
