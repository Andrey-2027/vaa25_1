package org.ip.rls;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.ip.security.CurrentUser;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
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
 * само лезет в БД мимо уже покрытых AbstractBaseService/AbstractTableSectionService/
 * LookupService/ReferenceCheckService. Само по себе ensureRlsEnabled никогда не роняет
 * "тихую" утечку — оно либо включает фильтр, либо (RlsContext.isBypassed()) сознательно
 * его не включает; тихой утечкой остаётся только полностью не вызванный метод.
 */
@Component
public class RlsFilterActivator {

    private static final String ACTIVATED_PROPERTY = "org.ip.rls.activated";

    private final RlsDimensionRegistry dimensionRegistry;
    private final RlsReadableIdsCache readableIdsCache;

    public RlsFilterActivator(RlsDimensionRegistry dimensionRegistry, RlsReadableIdsCache readableIdsCache) {
        this.dimensionRegistry = dimensionRegistry;
        this.readableIdsCache = readableIdsCache;
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
        if (Boolean.TRUE.equals(entityManager.getProperties().get(ACTIVATED_PROPERTY))) {
            return;
        }

        Session session = entityManager.unwrap(Session.class);
        String username = CurrentUser.username();

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
                continue;
            }
            session.enableFilter(dimension).setParameterList("allowedIds", allowedIds);
        }

        entityManager.setProperty(ACTIVATED_PROPERTY, Boolean.TRUE);
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
    public <T> T withRlsDisabled(EntityManager entityManager, Supplier<T> action) {
        Session session = entityManager.unwrap(Session.class);
        List<String> disabled = new ArrayList<>();
        for (String dimension : dimensionRegistry.dimensions()) {
            if (dimensionRegistry.kindOf(dimension) == RlsDimensionKind.CHECK_ONLY) {
                continue; // никогда не был включён — см. ensureRlsEnabled
            }
            if (session.getEnabledFilter(dimension) != null) {
                session.disableFilter(dimension);
                disabled.add(dimension);
            }
        }
        try {
            return action.get();
        } finally {
            String username = CurrentUser.username();
            for (String dimension : disabled) {
                List<Long> allowedIds = readableIdsCache.getReadableIds(dimension, username);
                if (allowedIds != null) {
                    session.enableFilter(dimension).setParameterList("allowedIds", allowedIds);
                }
            }
        }
    }
}