package org.ip.config;

import java.util.Map;

import org.ipro.report.data.ReportDataset;
import org.ipro.report.data.EntityRef;
import org.ipro.query.GuardResult;
import org.ipro.query.ReportPreviewService;
import org.ipro.query.ReportQueryGuard;
import org.ipro.jr.run.JpqlDatasetRunner;
import org.springframework.stereotype.Component;

/**
 * Единая точка выполнения пользовательского JPQL для всех движков отчётов
 * (UniDR, JR/jrxml, REST-эндпоинт превью): guard → preview.
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
 * UniDR — стандартный {@link ReportQueryGuard#MAX_COLUMNS}, JR/JSS —
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
     * Выполнение со стандартным лимитом колонок (UniDR).
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
     * Entity-значения (EntityRef из reportstudio) -> строка отображения,
     * иначе JSON выдаёт "[object Object]".
     */
    public static Object displayValue(Object value) {
        if (value instanceof EntityRef ref) {
            return ref.caption() != null ? ref.caption() : String.valueOf(ref.id());
        }
        return value;
    }
}
