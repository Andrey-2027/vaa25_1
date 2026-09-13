package org.ipro.data;

import org.ipro.fetch.plan.FetchScenario;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Immutable unpaged read request canonical executor'а (C4.1).
 *
 * <p>Unbounded {@code findAll()} не является golden path интерактивного UI (ADR-0007 §1),
 * но остаётся частью compatibility-контракта {@code BaseService} и потому выполняется
 * через ту же границу, а не собственным запросом потребителя.</p>
 *
 * @param type            entity type
 * @param scenario        fetch-сценарий
 * @param filter          Specification или {@code null}
 * @param sort            sort или {@link Pageable#unpaged()}
 * @param additionalPaths дополнительные JPA-пути
 */
public record ListRead<T>(Class<T> type,
                          FetchScenario scenario,
                          Specification<T> filter,
                          Pageable sort,
                          Collection<String> additionalPaths) {

    public ListRead {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(scenario, "scenario must not be null");
        sort = sort == null ? Pageable.unpaged() : sort;
        additionalPaths = List.copyOf(additionalPaths == null ? List.<String>of() : additionalPaths);
    }

    public static <T> ListRead<T> of(Class<T> type, FetchScenario scenario) {
        return new ListRead<>(type, scenario, null, Pageable.unpaged(), List.of());
    }
}
