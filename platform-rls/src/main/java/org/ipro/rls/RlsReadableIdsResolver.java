package org.ipro.rls;

import java.util.List;

/**
 * Узкий seam (F-REST-READ-4 §4.1): источник readable-ids, который потребляет
 * {@link RlsFilterActivator}. Default-реализация — {@link RlsReadableIdsCache}:
 * session-scoped UI-кэш с прежней семантикой (AccessService, username,
 * {@link AccessGrantVersion}). HTTP-адаптер подменяет источник на кэш, ограниченный
 * одним запросом, не меняя ни AccessService, ни саму семантику.
 *
 * <p>Реализация обязана сохранять контракт
 * {@link AccessService#getReadableIds(String, String)}: {@code null} — wildcard-грант
 * (доступ без ограничений по измерению), пустой список никогда не возвращается —
 * вместо него {@link AccessService#NO_ACCESS_SENTINEL}.</p>
 */
@FunctionalInterface
public interface RlsReadableIdsResolver {

    /** null — доступ без ограничений по измерению (wildcard-грант). */
    List<Long> getReadableIds(String dimension, String username);
}
