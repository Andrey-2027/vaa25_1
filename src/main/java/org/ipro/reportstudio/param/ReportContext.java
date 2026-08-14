package org.ipro.reportstudio.param;

import java.time.Instant;
import java.util.List;

/**
 * Контекст запуска отчёта (Фаза 3). Собирается вызывающей стороной при запуске:
 * из каталога — пустой контекст (параметры ведут себя как обычные FORM),
 * с формы документа/из грида — сущность/список выбранных записей.
 * <p>
 * {@code now} фиксируется ОДИН раз при построении контекста — все параметры
 * COMPUTED(NOW) обязаны получить один и тот же момент, независимо от порядка
 * резолвинга.
 * <p>
 * Сопоставление контекста с сущностным параметром — по
 * {@link #matches(Class)}: параметр (например, {@code entityClass=Journal})
 * покрывает контекст производного класса ({@code ReceivingDocument} — наследник
 * не нужен, правило из плана — {@code isAssignableFrom}, а не равенство классов).
 */
public record ReportContext(Class<?> entityClass, Object entityId, List<?> selectedIds,
                            String viewId, String user, Instant now) {

    public ReportContext {
        now = now == null ? Instant.now() : now;
        selectedIds = selectedIds == null ? List.of() : List.copyOf(selectedIds);
    }

    /** Пустой контекст — запуск без документа (из каталога отчётов). */
    public static ReportContext empty(String user) {
        return new ReportContext(null, null, List.of(), null, user, Instant.now());
    }

    /** Пустой контекст с фиксированным моментом (тесты, единый now в цепочке вызовов). */
    public static ReportContext empty(String user, Instant now) {
        return new ReportContext(null, null, List.of(), null, user, now);
    }

    public static ReportContext of(Class<?> entityClass, Object entityId, List<?> selectedIds,
                                   String viewId, String user, Instant now) {
        return new ReportContext(entityClass, entityId, selectedIds, viewId, user, now);
    }

    /**
     * Покрывает ли контекст сущностный параметр: {@code paramEntityClass}
     * assignable from {@code entityClass} контекста (см. решение в плане).
     */
    public boolean matches(Class<?> paramEntityClass) {
        return paramEntityClass != null && entityClass != null
            && paramEntityClass.isAssignableFrom(entityClass);
    }
}
