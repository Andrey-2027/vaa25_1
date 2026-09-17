package org.ipro.data;

import org.ipro.fetch.plan.FetchScenario;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Immutable detail read request canonical executor'а (C4.1).
 *
 * <p>Сценарий — часть запроса, а не константа исполнителя. Одна и та же загрузка по id
 * используется двумя каналами, и это разные планы: карточка формы читается сценарием
 * {@link FetchScenario#DETAIL}, а значение для автокомплита/формы выбора —
 * {@link FetchScenario#LOOKUP}, потому что только LOOKUP несёт объявленные зависимости
 * выбора ({@code @Lookup(fetch = ...)}), которых в DETAIL нет. Пока сценарий был жёстко
 * зашит на DETAIL, lookup по id терял ровно эти зависимости.</p>
 *
 * @param type            entity type
 * @param id              идентификатор
 * @param scenario        {@code DETAIL} (карточка) либо {@code LOOKUP} (значение выбора)
 * @param additionalPaths дополнительные JPA-пути
 */
public record DetailRead<T>(Class<T> type,
                            Object id,
                            FetchScenario scenario,
                            Collection<String> additionalPaths) {

    public DetailRead {
        Objects.requireNonNull(type, "type must not be null");
        id = Objects.requireNonNull(id, "id must not be null");
        if (scenario != FetchScenario.DETAIL && scenario != FetchScenario.LOOKUP) {
            throw new IllegalArgumentException("detail read supports DETAIL and LOOKUP, was "
                + scenario);
        }
        additionalPaths = List.copyOf(additionalPaths == null ? List.<String>of() : additionalPaths);
    }

    /** Карточка формы: сценарий {@code DETAIL}. */
    public static <T> DetailRead<T> of(Class<T> type, Object id) {
        return new DetailRead<>(type, id, FetchScenario.DETAIL, List.of());
    }

    /** Значение выбора: сценарий {@code LOOKUP} с его объявленными зависимостями. */
    public static <T> DetailRead<T> lookup(Class<T> type, Object id,
                                           Collection<String> additionalPaths) {
        return new DetailRead<>(type, id, FetchScenario.LOOKUP, additionalPaths);
    }
}
