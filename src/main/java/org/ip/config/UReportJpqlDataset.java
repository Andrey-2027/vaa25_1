package org.ip.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.ipro.reportstudio.data.QueryField;
import org.ipro.reportstudio.data.ReportDataset;
import org.ipro.reportstudio.data.ReportRow;
import org.ipro.reportstudio.query.GuardResult;
import org.ipro.reportstudio.query.ReportPreviewService;
import org.ipro.reportstudio.query.ReportQueryGuard;
import org.springframework.stereotype.Component;

import com.bstek.ureport.definition.datasource.JpqlDatasetExecutor;

/**
 * Мостик UReport -> JPQL: выполнение JPQL через существующий конвейер
 * reportstudio (guard -> preview -> executor).
 *
 * RLS применяется принудительно: ReportQueryExecutor включает фильтры
 * RlsFilterActivator'ом под текущим аутентифицированным пользователем.
 *
 * Два способа использования в дизайнере UReport:
 *
 * 1. (B2, рекомендуемый) Обычный SQL-датасет на любом источнике, текст
 *    в поле SQL начинается с маркера "jpql:":
 *        jpql:
 *        select a.id as id, a.codeSpec as codeSpec from PrdSpec a
 *    Параметры датасета (таблица параметров в диалоге) передаются в JPQL
 *    как named-биндинги (имя параметра = имя :param).
 *    Выполняется через {@link #execute(String, Map)}.
 *
 * 2. (B1, legacy) Bean-датасет: Bean ID = ureportJpqlDataset, метод dataset,
 *    текст JPQL в поле "Имя датасета". Выполняется через {@link #dataset}.
 */
@Component("ureportJpqlDataset")
public class UReportJpqlDataset implements JpqlDatasetExecutor {

    /** Ограничение размера выборки (защита от тяжёлых запросов из дизайнера). */
    private static final int MAX_ROWS = 10_000;
    /** Таймаут выполнения JPQL, мс. */
    private static final long TIMEOUT_MS = 30_000;

    private final ReportQueryGuard queryGuard;
    private final ReportPreviewService previewService;

    public UReportJpqlDataset(ReportQueryGuard queryGuard, ReportPreviewService previewService) {
        this.queryGuard = queryGuard;
        this.previewService = previewService;
    }

    /**
     * Точка вызова движком UReport для bean-датасета (вариант B1,
     * сигнатура фиксирована BeanDatasetDefinition): текст JPQL передаётся
     * в поле "Имя датасета".
     */
    public List<Map<String, Object>> dataset(String datasourceName, String datasetName,
                                             Map<String, Object> parameters) {
        String jpql = resolveJpqlFromName(datasetName);
        return runJpql(jpql, parameters);
    }

    /**
     * Точка вызова для SQL-датасета с маркером "jpql:" (вариант B2).
     */
    @Override
    public List<Map<String, Object>> execute(String jpql, Map<String, Object> parameters) {
        return runJpql(jpql, parameters);
    }

    private List<Map<String, Object>> runJpql(String jpql, Map<String, Object> parameters) {
        Map<String, Object> bindings = parameters == null ? Map.of() : parameters;

        GuardResult guard = queryGuard.guard(jpql, bindings.keySet());
        if (!guard.allowed()) {
            throw new IllegalStateException(
                    "JPQL отклонён guard'ом: " + String.join("; ", guard.errors()));
        }

        ReportDataset ds = previewService.preview(jpql, bindings,
                guard.selectFields(), MAX_ROWS, TIMEOUT_MS);
        return toMaps(ds);
    }

    private String resolveJpqlFromName(String datasetName) {
        if (datasetName == null || datasetName.isBlank()) {
            throw new IllegalArgumentException(
                    "Имя датасета должно содержать JPQL-запрос, начинающийся с \"select\"");
        }
        String jpql = datasetName.strip();
        if (!jpql.toLowerCase().startsWith("select")) {
            throw new IllegalArgumentException(
                    "Ожидается JPQL-запрос (текст в поле \"Имя датасета\" должен начинаться с \"select\")");
        }
        return jpql;
    }

    private List<Map<String, Object>> toMaps(ReportDataset ds) {
        QueryField[] fields = ds.fields();
        List<Map<String, Object>> result = new ArrayList<>(ds.rowCount());
        for (ReportRow row : ds.rows()) {
            Map<String, Object> map = new LinkedHashMap<>(fields.length * 2);
            for (int i = 0; i < fields.length; i++) {
                // точки в именах (алиасы вида a.journal) ломают биндинг ячеек UReport
                map.put(fields[i].name().replace('.', '_'), toDisplayValue(row.value(i)));
            }
            result.add(map);
        }
        return result;
    }

    /**
     * Entity-значения (EntityRef из reportstudio) -> строка отображения,
     * иначе UReport/JSON выдаёт "[object Object]".
     */
    private Object toDisplayValue(Object value) {
        if (value instanceof org.ipro.reportstudio.data.EntityRef ref) {
            return ref.caption() != null ? ref.caption() : String.valueOf(ref.id());
        }
        return value;
    }
}
