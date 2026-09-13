package org.ipro.data;

import org.ipro.fetch.plan.FetchScenario;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Immutable paged read request canonical executor'а (C4.1).
 *
 * @param type            entity type
 * @param scenario        fetch-сценарий; дополнительные пути только расширяют его
 * @param filter          Specification или {@code null} (создание своего filter DSL не входит в C4)
 * @param pageable        paging и sort
 * @param additionalPaths дополнительные JPA-пути динамического вида
 */
public record PageRead<T>(Class<T> type,
                          FetchScenario scenario,
                          Specification<T> filter,
                          Pageable pageable,
                          Collection<String> additionalPaths) {

    public PageRead {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(scenario, "scenario must not be null");
        Objects.requireNonNull(pageable, "pageable must not be null");
        additionalPaths = List.copyOf(additionalPaths == null ? List.<String>of() : additionalPaths);
    }

    public static <T> PageRead<T> of(Class<T> type, FetchScenario scenario,
                                     Specification<T> filter, Pageable pageable) {
        return new PageRead<>(type, scenario, filter, pageable, List.of());
    }
}
