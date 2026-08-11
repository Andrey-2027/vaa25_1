package org.ipro.rls;

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
 * инспектор вызывается Hibernate'ом (public no-arg конструктор, без Spring),
 * поэтому guard подключён к нему статическим мостом по образцу SqlTimingBridge:
 * Spring-конфигурация ставит экземпляр через {@link #install}, а состояние
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
 * только фиксирует; AOP-принуждение — отдельное отложенное решение (см. план).
 */
public final class RlsStatementGuard {

    private static final Logger log = LoggerFactory.getLogger(RlsStatementGuard.class);

    private static volatile RlsStatementGuard instance;

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

    /** Spring-конфигурация выставляет готовый guard; до этого момента inspect — no-op. */
    public static void install(RlsStatementGuard guard) {
        instance = guard;
    }

    /** Вызывается из StatementInspector на каждый SQL-текст. */
    public static void inspect(String sql) {
        RlsStatementGuard guard = instance;
        if (guard != null) {
            guard.audit(sql);
        }
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

    private void audit(String sql) {
        if (sql == null || sql.isBlank() || RlsContext.isBypassed() || Boolean.TRUE.equals(CONSENT.get())) {
            return;
        }
        String normalized = sql.stripLeading().toLowerCase(Locale.ROOT);
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
