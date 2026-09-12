package org.ipro.rls;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Включает Hibernate @Filter на текущей сессии — по одному вызову {@link #ensureRlsEnabled}
 * на Hibernate Session, а не на каждый вызов сервиса.
 *
 * Отказ от отдельного хука на Vaadin round-trip (как обсуждалось изначально): вместо
 * того чтобы полагаться на точный момент срабатывания VaadinRequestInterceptor
 * относительно момента, когда OSIV открывает EntityManager для запроса — метод
 * самоактивируется ЛЕНИВО, при первом обращении к нему в рамках текущей Hibernate
 * Session (см. флаг ACTIVATED_PROPERTY на самом EntityManager — умирает вместе с ним,
 * никакой ручной очистки не требуется). Под OSIV (spring.jpa.open-in-view, включён по
 * умолчанию и ничем не переопределён в application.properties) EntityManager живёт
 * весь HTTP round-trip Vaadin-приложения — то есть эффект тот же самый: один расчёт и
 * одно включение фильтра за round-trip, — но без зависимости от порядка срабатывания
 * Vaadin-хуков, который иначе пришлось бы отдельно проверять на реальном сервере.
 *
 * Как следствие, единственный оставшийся риск того же типа, что и с 5-6 путями чтения
 * данных (см. обсуждение RLS) — забыть вызвать ensureRlsEnabled в НОВОМ месте, которое
 * само лезет в БД мимо уже покрытых AbstractBaseService/GenericOwnedSectionService/
 * LookupService/ReferenceCheckService. Само по себе ensureRlsEnabled никогда не роняет
 * "тихую" утечку — оно либо включает фильтр, либо (RlsContext.isBypassed()) сознательно
 * его не включает; тихой утечкой остаётся только полностью не вызванный метод.
 */
@Component
public class RlsFilterActivator {

    private static final String ACTIVATED_PROPERTY = "org.ipro.rls.activated";

    private record Activation(String username, long grantVersion) implements java.io.Serializable {
    }

    private final RlsDimensionRegistry dimensionRegistry;
    private final RlsReadableIdsCache readableIdsCache;
    private final RlsCurrentUser currentUser;
    private final RlsBypassAudit bypassAudit;

    public RlsFilterActivator(RlsDimensionRegistry dimensionRegistry, RlsReadableIdsCache readableIdsCache,
                              RlsCurrentUser currentUser) {
        this(dimensionRegistry, readableIdsCache, currentUser, RlsBypassAudit.loggingOnly());
    }

    public RlsFilterActivator(RlsDimensionRegistry dimensionRegistry,
                              RlsReadableIdsCache readableIdsCache,
                              RlsCurrentUser currentUser,
                              RlsBypassAudit bypassAudit) {
        this.dimensionRegistry = dimensionRegistry;
        this.readableIdsCache = readableIdsCache;
        this.currentUser = currentUser;
        this.bypassAudit = bypassAudit;
    }

    /**
     * Идемпотентно: второй и последующие вызовы в рамках той же Hibernate Session —
     * практически no-op (одна проверка свойства на EntityManager).
     *
     * RlsContext.isBypassed() — фоновая задача, сознательно работающая без RLS
     * (см. {@link RlsContext}) — фильтры не включаются вообще, метод сразу возвращается.
     */
    public void ensureRlsEnabled(EntityManager entityManager) {
        if (RlsContext.isBypassed()) {
            return;
        }
        Session session = entityManager.unwrap(Session.class);
        String username = currentUser.username();
        Activation requested = new Activation(username, AccessGrantVersion.current());
        if (requested.equals(entityManager.getProperties().get(ACTIVATED_PROPERTY))) {
            return;
        }

        // A reused session must never retain predicates of another subject or grant version.
        for (String dimension : dimensionRegistry.dimensions()) {
            if (dimensionRegistry.kindOf(dimension) == RlsDimensionKind.FILTERABLE
                    && session.getEnabledFilter(dimension) != null) {
                session.disableFilter(dimension);
            }
        }

        // набор измерений, обработанных для ЭТОЙ сессии (включён фильтр ИЛИ сознательно
        // пропущен из-за wildcard-гранта) — для RLS-канарейки RlsStatementGuard
        Set<String> processed = new HashSet<>();

        for (String dimension : dimensionRegistry.dimensions()) {
            if (dimensionRegistry.kindOf(dimension) == RlsDimensionKind.CHECK_ONLY) {
                // CHECK_ONLY — участвует только в write-guard'е/getReadableIds (навигация),
                // никакого @Filter/@FilterDef для него не существует — enableFilter тут
                // бросил бы UnknownFilterException.
                continue;
            }
            List<Long> allowedIds = readableIdsCache.getReadableIds(dimension, username);
            if (allowedIds == null) {
                // Wildcard-грант (dimensionValueId = null или dimension = "*") — доступ без
                // ограничений: фильтр сознательно НЕ включаем (см. обсуждение — @Filter не
                // умеет выразить "включён, но ничего не фильтрует" через null-параметр).
                processed.add(dimension);
                continue;
            }
            session.enableFilter(dimension).setParameterList("allowedIds", allowedIds);
            processed.add(dimension);
        }

        entityManager.setProperty(ACTIVATED_PROPERTY, requested);
        RlsStatementGuard.markProcessed(processed);
    }

    /**
     * Выполняет action с ВЫКЛЮЧЕННЫМИ на время вызова RLS-фильтрами и восстанавливает
     * их (с теми же allowedIds) после — а НЕ просто "не включает новые" (RlsContext.
     * isBypassed() тут не поможет: если фильтр уже включён раньше в ЭТОМ ЖЕ round-trip
     * другим запросом, он остаётся включённым до явного disableFilter — RlsContext
     * влияет только на код, который сам его проверяет, а не на состояние Session).
     *
     * Нужен там, где сам SELECT не должен учитывать RLS текущего пользователя —
     * например, проверка ссылочной целостности перед удалением (ReferenceCheckService):
     * если удаляемая запись помечена @RlsDimension (как PrdSpec — через Journal), то
     * COUNT ссылок на неё должен видеть ВСЕ ссылки, а не только те, что под доступными
     * текущему пользователю измерениями — иначе можно удалить запись, на которую есть
     * ссылки под недоступным пользователю измерением, и получить рассинхрон в БД,
     * невидимый удалившему.
     */
    public <T> T withReferenceIntegrityCheck(EntityManager entityManager, Supplier<T> action) {
        return withRlsDisabled(entityManager, RlsBypassScope.REFERENCE_INTEGRITY_CHECK,
            "count all references before delete", action);
    }

    public <T> T withDimensionAdministration(EntityManager entityManager, String dimension,
                                              Supplier<T> action) {
        String normalized = dimension == null ? "" : dimension.trim().toUpperCase(java.util.Locale.ROOT);
        String reason = switch (normalized) {
            case "JOURNAL" -> "list all JOURNAL dimension values";
            case "BRANCH" -> "list all BRANCH dimension values";
            default -> throw new IllegalArgumentException(
                "Dimension is not approved for privileged administration: " + dimension);
        };
        return withRlsDisabled(entityManager, RlsBypassScope.RLS_ADMINISTRATION, reason, action);
    }

    private <T> T withRlsDisabled(EntityManager entityManager,
                                  RlsBypassScope scope,
                                  String reason,
                                  Supplier<T> action) {
        String requestedBy = currentUser.requireAuthenticatedUsername();
        Session session = entityManager.unwrap(Session.class);
        Map<String, List<Long>> disabled = new LinkedHashMap<>();
        for (String dimension : dimensionRegistry.dimensions()) {
            if (dimensionRegistry.kindOf(dimension) != RlsDimensionKind.CHECK_ONLY
                    && session.getEnabledFilter(dimension) != null) {
                disabled.put(dimension, readableIdsCache.getReadableIds(dimension, requestedBy));
            }
        }
        try {
            T result = RlsContext.callAsSystem(scope, reason, requestedBy, () -> {
                disabled.keySet().forEach(session::disableFilter);
                try {
                    return action.get();
                } finally {
                    disabled.forEach((dimension, allowedIds) -> {
                        if (allowedIds != null) {
                            session.enableFilter(dimension).setParameterList("allowedIds", allowedIds);
                        }
                    });
                }
            });
            bypassAudit.record(scope, reason, requestedBy, true, null);
            return result;
        } catch (RuntimeException | Error failure) {
            bypassAudit.record(scope, reason, requestedBy, false, failure);
            throw failure;
        }
    }

    /**
     * Legacy source-compatibility shim. Untyped bypasses are deliberately disabled:
     * callers must state a narrow scope and reason.
     */
    @Deprecated(forRemoval = false)
    public <T> T withRlsDisabled(EntityManager entityManager, Supplier<T> action) {
        throw new UnsupportedOperationException(
            "Untyped RLS bypass is disabled; use withRlsDisabled(entityManager, scope, reason, action)");
    }
}
