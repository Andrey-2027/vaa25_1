package org.ip.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.ipro.reportstudio.data.QueryField;
import org.ipro.reportstudio.data.ReportDataset;
import org.ipro.reportstudio.data.ReportRow;
import org.ipro.reportstudio.data.EntityRef;
import org.ipro.reportstudio.query.GuardResult;
import org.ipro.reportstudio.query.ReportPreviewService;
import org.ipro.reportstudio.query.ReportQueryGuard;
import org.ipro.jr.run.JpqlDatasetRunner;
import org.springframework.stereotype.Component;

/**
 * Единая точка выполнения пользовательского JPQL для всех движков отчётов
 * (UDR, UReport B1/B2, JR/jrxml, REST-эндпоинт превью): guard → preview.
 *
 * <p>RLS применяется принудительно: {@link ReportPreviewService} выполняет
 * запрос в транзакции через {@code ReportQueryExecutor}, который включает
 * фильтры {@code RlsFilterActivator}'ом под текущим аутентифицированным
 * пользователем. Обход этого сервиса = обход guard'а и RLS.</p>
 *
 * <p>Лимиты общие для всех движков:
 * <ul>
 * <li>{@link #MAX_ROWS} / {@link #TIMEOUT_MS} — защита от тяжёлых запросов;</li>
 * <li>лимит колонок задаёт вызывающий движок ({@code maxColumns}):
 * UDR/UReport — стандартный {@link ReportQueryGuard#MAX_COLUMNS}, JR/JSS —
 * расширенный (pixel-perfect корпоративный документ шире ad-hoc отчёта).</li>
 * </ul></p>
 */
@Component
public class JpqlRunService implements JpqlDatasetRunner {

    /** Ограничение размера выборки (защита от тяжёлых запросов из дизайнера/каталога). */
    public static final int MAX_ROWS = 10_000;
    /** Таймаут выполнения JPQL, мс. */
    public static final long TIMEOUT_MS = 30_000;

    private final ReportQueryGuard queryGuard;
    private final ReportPreviewService previewService;

    public JpqlRunService(ReportQueryGuard queryGuard, ReportPreviewService previewService) {
        this.queryGuard = queryGuard;
        this.previewService = previewService;
    }

    /**
     * Выполнение со стандартным лимитом колонок (UDR/UReport).
     * Имена полей результата — исходные алиасы SELECT.
     */
    public ReportDataset run(String jpql, Map<String, Object> bindings) {
        return run(jpql, bindings, ReportQueryGuard.MAX_COLUMNS);
    }

    /**
     * Выполнение JPQL: guard (SELECT-only, двусторонний :param по именам
     * переданных биндингов, RLS entity-access, лимит колонок движка) →
     * preview (транзакция + RLS-фильтры).
     *
     * @param maxColumns лимит колонок вызывающего движка (см. Ф1.2 плана)
     */
    @Override
    public ReportDataset run(String jpql, Map<String, Object> bindings, int maxColumns) {
        Map<String, Object> safeBindings = bindings == null ? Map.of() : bindings;

        GuardResult guard = queryGuard.guard(jpql, safeBindings.keySet(), Map.of(), maxColumns);
        if (!guard.allowed()) {
            throw new IllegalStateException(
                    "JPQL отклонён guard'ом: " + String.join("; ", guard.errors()));
        }

        return previewService.preview(jpql, safeBindings,
                guard.selectFields(), MAX_ROWS, TIMEOUT_MS);
    }

    /**
     * Выполнение с UReport-семантикой имён: точки в алиасах (вид `a.journal`)
     * заменяются на подчёркивания — точки ломают биндинг ячеек UReport —
     * а entity-значения ({@link EntityRef}) переводятся в отображаемую строку,
     * иначе UReport/JSON выдаёт "[object Object]".
     */
    public List<Map<String, Object>> runAsMaps(String jpql, Map<String, Object> bindings) {
        ReportDataset ds = run(jpql, bindings);
        QueryField[] fields = ds.fields();
        List<Map<String, Object>> result = new ArrayList<>(ds.rowCount());
        for (ReportRow row : ds.rows()) {
            Map<String, Object> map = new LinkedHashMap<>(fields.length * 2);
            for (int i = 0; i < fields.length; i++) {
                map.put(fields[i].name().replace('.', '_'), displayValue(row.value(i)));
            }
            result.add(map);
        }
        return result;
    }

    /**
     * Entity-значения (EntityRef из reportstudio) -> строка отображения,
     * иначе UReport/JSON выдаёт "[object Object]".
     */
    public static Object displayValue(Object value) {
        if (value instanceof EntityRef ref) {
            return ref.caption() != null ? ref.caption() : String.valueOf(ref.id());
        }
        return value;
    }
}
