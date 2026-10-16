package org.ipro.rls;

import org.ipro.telemetry.api.SqlStatementAudit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Наблюдательная канарейка RLS (Фаза 6 RLS-плана): фиксирует SELECT по таблицам
 * RLS-сущностей, выполненные БЕЗ включённого Hibernate-фильтра — "тихую утечку",
 * когда какой-то путь чтения забыл {@link RlsFilterActivator#ensureRlsEnabled}.
 *
 * Композиция в {@code org.ipro.telemetry.core.SqlStatementInspector} (Hibernate
 * держит один StatementInspector — второй ставить нельзя, затрёт телеметрию):
 * инспектор вызывается Hibernate'ом (public no-arg конструктор, без Spring), поэтому
 * guard подключён к нему через нейтральный шов
 * {@link org.ipro.telemetry.core.SqlStatementAuditBridge}: конфигурация RLS ставит
 * экземпляр, телеметрия о RLS не знает (D2 → D3: до этого здесь был прямой статический
 * мост из телеметрии в RLS, из-за чего наблюдение зависело от слоя принуждения). Состояние
 * "фильтры включены" приходит от {@link RlsFilterActivator} через
 * {@link #markProcessed} — у StatementInspector нет доступа к текущей Hibernate
 * Session (аналог проблемы "нельзя проверить session.getEnabledFilter(dim)").
 *
 * Точность по сессии: {@link #markProcessed} запоминает измерения, обработанные
 * активатором для текущей сессии ПОТОКА (в приложении на поток — одна активная
 * OSIV-сессия). Переиспользование потоков пулом HTTP-обработчиков закрывается
 * сбросом на границе каждого запроса ({@link RlsGuardRequestFilter}); фоновые
 * задачи идут через {@link RlsContext#isBypassed()}; сознательное выключение
 * фильтров (ReferenceCheckService) — через consent {@link #beginConsent}/
 * {@link #endConsent}, которым обёрнут {@link RlsFilterActivator#withRlsDisabled}.
 *
 * Режимы: прод — ERROR-лог + счётчик ({@link #violationCount}); тесты —
 * коллектор нарушений ({@link #violations}) при {@code rls.guard.strict=true}
 * (свойство, управляется тестовым конфигом). Канарейка НИЧЕГО не блокирует —
 * только фиксирует; обязательная server-side граница живёт в
 * {@link RlsPolicyEnforcer} и {@link RlsRepositoryEnforcementAspect}.
 */
public final class RlsStatementGuard implements SqlStatementAudit {

    private static final Pattern FROM_TABLE = Pattern.compile("\\bfrom\\s+((?:[\\w\\\"`]+\\.)?[\\w\\\"`]+)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern RECORDER_TYPE_PARAMETER = Pattern.compile(
        "(?:[\\w]+\\.)?[\\\"`]?recorder_type[\\\"`]?\\s*=\\s*\\?", Pattern.CASE_INSENSITIVE);
    private static final Pattern RECORDER_ID_PARAMETER = Pattern.compile(
        "(?:[\\w]+\\.)?[\\\"`]?recorder_id[\\\"`]?\\s*=\\s*\\?", Pattern.CASE_INSENSITIVE);

    private static final Logger log = LoggerFactory.getLogger(RlsStatementGuard.class);

    /** Измерения, обработанные активатором для текущей сессии потока (включён фильтр ИЛИ
     *  сознательно пропущен из-за wildcard-гранта). Отсутствие измерения = фильтр не включался. */
    private static final ThreadLocal<Set<String>> PROCESSED = new ThreadLocal<>();

    /** Consent-окно withRlsDisabled: фильтры сознательно выключены на время действия. */
    private static final ThreadLocal<Boolean> CONSENT = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static final AtomicLong VIOLATION_COUNT = new AtomicLong();
    private static final List<String> RECORDED_VIOLATIONS = Collections.synchronizedList(new ArrayList<>());

    private record TableCheck(String table, Set<String> dimensions, Pattern pattern) {
    }

    private final List<TableCheck> tables;
    private final boolean strict;

    public RlsStatementGuard(RlsDimensionRegistry dimensionRegistry, boolean strict) {
        this.strict = strict;
        List<TableCheck> checks = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : dimensionRegistry.filterableDimensionsByTable().entrySet()) {
            checks.add(new TableCheck(entry.getKey(), entry.getValue(),
                Pattern.compile("\\b" + Pattern.quote(entry.getKey()) + "\\b")));
        }
        this.tables = List.copyOf(checks);
    }

    /** Активатор отметил, что для текущей сессии потока обработаны именно эти измерения. */
    public static void markProcessed(Set<String> processed) {
        PROCESSED.set(Set.copyOf(processed));
    }

    /** Граница HTTP-запроса: состояние сессии предыдущего запроса на этом потоке более недействительно. */
    public static void clearSession() {
        PROCESSED.remove();
    }

    public static void beginConsent() {
        CONSENT.set(Boolean.TRUE);
    }

    public static void endConsent() {
        CONSENT.remove();
    }

    /** Полный сброс состояния (включая коллектор/счётчик) — для тестов. */
    public static void reset() {
        PROCESSED.remove();
        CONSENT.remove();
        RECORDED_VIOLATIONS.clear();
        VIOLATION_COUNT.set(0);
    }

    /** Собранные нарушения (только strict-режим). */
    public static List<String> violations() {
        synchronized (RECORDED_VIOLATIONS) {
            return List.copyOf(RECORDED_VIOLATIONS);
        }
    }

    /** Счётчик нарушений (все режимы). */
    public static long violationCount() {
        return VIOLATION_COUNT.get();
    }

    /** Вызывается швом телеметрии на каждый SQL-текст, подготовленный Hibernate. */
    @Override
    public void audit(String sql) {
        if (sql == null || sql.isBlank()) {
            return;
        }
        String normalized = sql.stripLeading().toLowerCase(Locale.ROOT);
        if (RlsContext.isBypassed()) {
            validatePrivilegedStatement(sql, normalized);
            return;
        }
        if (Boolean.TRUE.equals(CONSENT.get())) {
            return;
        }
        if (!normalized.startsWith("select")) {
            return;
        }
        Set<String> processed = PROCESSED.get();
        if (processed == null) {
            processed = Set.of();
        }
        for (TableCheck check : tables) {
            if (!check.pattern().matcher(normalized).find()) {
                continue;
            }
            for (String dimension : check.dimensions()) {
                if (!processed.contains(dimension)) {
                    report(check.table(), dimension, sql);
                }
            }
        }
    }

    private void validatePrivilegedStatement(String sql, String normalized) {
        RlsBypassScope scope = RlsContext.currentScope();
        if (!normalized.startsWith("select")) {
            throw new RlsAccessDeniedException(
                "RLS bypass scopes are read-only; rejected SQL: " + sql);
        }
        if (scope == RlsBypassScope.REFERENCE_INTEGRITY_CHECK) {
            if (!normalized.matches("select\\s+count\\s*\\(.*")) {
                throw new RlsAccessDeniedException(
                    "Reference-integrity bypass may execute COUNT queries only");
            }
            return;
        }
        if (scope == RlsBypassScope.RLS_ADMINISTRATION) {
            String allowedTable = scope.administrationTable(RlsContext.currentReason());
            Set<String> registeredTables = RlsContext.currentApprovedTables();
            if (!registeredTables.isEmpty()) {
                if (normalized.contains(" join ") || normalized.contains(" union ") || normalized.contains(";")) {
                    throw new RlsAccessDeniedException("Dimension administration permits only source and grant subqueries");
                }
                var references = FROM_TABLE.matcher(normalized);
                while (references.find()) {
                    String reference = references.group(1);
                    String table = reference.substring(reference.lastIndexOf('.') + 1)
                        .replace("\"", "").replace("`", "").toLowerCase(Locale.ROOT);
                    if (!registeredTables.contains(table) && !table.equals("access_grant")) {
                        throw new RlsAccessDeniedException("Unapproved administration source table " + table);
                    }
                }
            }
            boolean allowedSeen = false;
            for (TableCheck check : tables) {
                if (!check.pattern().matcher(normalized).find()) {
                    continue;
                }
                if (!check.table().equalsIgnoreCase(allowedTable) && !registeredTables.contains(check.table().toLowerCase(Locale.ROOT))) {
                    throw new RlsAccessDeniedException(
                        "RLS administration bypass cannot query table " + check.table());
                }
                allowedSeen = true;
            }
            if (!allowedSeen) {
                throw new RlsAccessDeniedException(
                    "RLS administration bypass must query its approved dimension table");
            }
            return;
        }
        if (scope == RlsBypassScope.DEPENDENT_ROW_CLEANUP
                || scope == RlsBypassScope.RECORDER_SET_REPLACEMENT) {
            validateRecorderSetRead(sql, normalized);
            return;
        }
        throw new RlsAccessDeniedException("Unknown RLS bypass scope");
    }

    private static void validateRecorderSetRead(String sql, String normalized) {
        if (!normalized.startsWith("select ") || normalized.contains(" join ")
                || normalized.contains(" union ") || normalized.contains("; ")
                || normalized.indexOf("select") != normalized.lastIndexOf("select")) {
            throw new RlsAccessDeniedException(
                "Recorder-set RLS scope permits one direct SELECT only");
        }
        Set<String> approvedTables = RlsContext.currentApprovedTables();
        if (approvedTables.isEmpty()) {
            throw new RlsAccessDeniedException(
                "Recorder-set RLS scope requires an approved register table");
        }
        var from = FROM_TABLE.matcher(normalized);
        if (!from.find()) {
            throw new RlsAccessDeniedException("Recorder-set SELECT must identify its source table");
        }
        String tableReference = from.group(1);
        if (from.find()) {
            throw new RlsAccessDeniedException("Recorder-set SELECT cannot read multiple tables");
        }
        String table = tableReference.substring(tableReference.lastIndexOf('.') + 1)
            .replace("\"", "").replace("`", "").toLowerCase(Locale.ROOT);
        if (!approvedTables.contains(table)) {
            throw new RlsAccessDeniedException("Recorder-set SELECT used unapproved table " + table);
        }
        int whereStart = normalized.indexOf(" where ");
        if (whereStart < 0) {
            throw new RlsAccessDeniedException("Recorder-set SELECT requires an exact recorder predicate");
        }
        String where = normalized.substring(whereStart + 7);
        int orderStart = where.indexOf(" order by ");
        if (orderStart >= 0) where = where.substring(0, orderStart);
        if (where.contains(" or ")) {
            throw new RlsAccessDeniedException("Recorder-set SELECT cannot broaden its recorder predicate");
        }
        boolean hasType = false;
        boolean hasId = false;
        for (String conjunct : where.split("\\s+and\\s+")) {
            hasType |= RECORDER_TYPE_PARAMETER.matcher(conjunct).find();
            hasId |= RECORDER_ID_PARAMETER.matcher(conjunct).find();
        }
        if (!hasType || !hasId) {
            throw new RlsAccessDeniedException(
                "Recorder-set SELECT must bind recorder_type and recorder_id separately");
        }
    }

    private void report(String table, String dimension, String sql) {
        VIOLATION_COUNT.incrementAndGet();
        String message = "RLS: SELECT по таблице \"" + table + "\" без включённого Hibernate-фильтра измерения \"" +
            dimension + "\" (возможная тихая утечка): " + sql;
        log.error(message);
        if (strict) {
            RECORDED_VIOLATIONS.add(message);
        }
    }
}
