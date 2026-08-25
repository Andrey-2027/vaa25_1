package org.ip.reports;

import org.ipro.rls.RlsDimension;
import org.ipro.rls.RlsDimensionKind;

/**
 * Права доступа к отчётной подсистеме (CHECK_ONLY-измерения RLS).
 *
 * <p>Маркер без {@code @Subsystem}: плитку в меню не создаёт, но измерение
 * попадает в реестр RLS — грант на него выдаётся через существующий
 * админ-экран грантов (по образцу SETTINGS:*).</p>
 */
public final class ReportRights {

    private ReportRights() {
    }

    /**
     * Доступ к REST-эндпоинту произвольного JPQL-превью
     * ({@code POST /api/report-jpql/preview}): без этого права любой
     * аутентифицированный пользователь мог бы выполнять произвольные
     * SELECT-запросы мимо каталога шаблонов (план ReportJR-Jpql-Plan.md,
     * Ф3/Р5 — «аутентифицирован» недостаточно).
     */
    @RlsDimension(value = "REPORTS:JPQL_PREVIEW", kind = RlsDimensionKind.CHECK_ONLY)
    public interface JpqlPreview {}
}
