package org.ipro.query;

import org.ipro.report.data.ReportDataset;
import org.ipro.report.data.ReportDataset.ReferenceColumn;
import org.ipro.report.data.ReportRow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Выполняет запрос как снимок для экрана: запрашивает на строку больше лимита,
 * чтобы отличить «ровно N строк» от «N и ещё есть».
 *
 * <p>Политика запуска остаётся в {@link QueryLimits} и guard'е: снимок не
 * ослабляет RLS, не меняет запрос и не открывает второй путь выполнения — он
 * только отрезает лишнюю пробную строку от уже полученного {@link ReportDataset}.</p>
 */
public class QuerySnapshotService {

    /** Шов выполнения: позволяет проверить усечение без JPA и без мок-фреймворка. */
    @FunctionalInterface
    interface SnapshotRunner {
        ReportDataset preview(ReportQueryAssembler assembled, int maxRows, long timeoutMs);
    }

    private final SnapshotRunner runner;

    public QuerySnapshotService(ReportPreviewService previewService) {
        this(previewService::preview);
    }

    QuerySnapshotService(SnapshotRunner runner) {
        this.runner = runner;
    }

    public QuerySnapshot snapshot(ReportQueryAssembler assembled, int limit, long timeoutMs) {
        if (assembled == null) {
            throw new IllegalArgumentException("QuerySnapshotService: assembled обязателен");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("QuerySnapshotService: limit должен быть положительным");
        }
        int probeRows = limit == Integer.MAX_VALUE ? limit : limit + 1;
        ReportDataset probe = runner.preview(assembled, probeRows, timeoutMs);
        if (probe.rowCount() <= limit) {
            return new QuerySnapshot(probe, false, limit);
        }

        ReportRow[] rows = Arrays.copyOf(probe.rows(), limit);
        List<ReferenceColumn> references = new ArrayList<>(probe.referenceColumns().size());
        for (ReferenceColumn column : probe.referenceColumns()) {
            references.add(new ReferenceColumn(column.name(), column.javaType(),
                    column.values().subList(0, limit)));
        }
        return new QuerySnapshot(new ReportDataset(probe.fields(), rows, references), true, limit);
    }
}
