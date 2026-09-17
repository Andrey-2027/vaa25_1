package org.ipro.data;

import jakarta.persistence.EntityGraph;
import jakarta.persistence.EntityManager;
import org.ipro.fetch.instance.InstanceNameResolver;
import org.ipro.fetch.plan.FetchPlanRegistry;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.metadata.FetchGraphs;
import org.ipro.metadata.InstanceNameSource;
import org.ipro.metadata.MetadataResolver;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Единственная точка правила {@code scenario plan ∪ extras -> validate -> deepen once -> graph}
 * (C4.1, ADR-0007 §4).
 *
 * <p>Устраняет прежнюю асимметрию compatibility-путей: один углублял объединение
 * плана и дополнительных путей, а lookup строил graph из union без
 * углубления. Теперь объединение и углубление вычисляются здесь, а потребители
 * (read executor, aggregate section service) получают уже готовый граф.</p>
 *
 * <p>Когда {@link FetchPlanRegistry} подключён, пути берутся из него
 * ({@link FetchPlanRegistry#pathsWith}); registry уже содержит metadata-производные
 * зависимости, instance-name состав и объявленные {@code @Lookup.fetch} пути.</p>
 */
public final class ScenarioFetchGraphResolver {

    private final MetadataResolver metadataResolver;
    private final FetchPlanRegistry fetchPlanRegistry;
    private final InstanceNameResolver instanceNameResolver;

    public ScenarioFetchGraphResolver(MetadataResolver metadataResolver,
                                      FetchPlanRegistry fetchPlanRegistry,
                                      InstanceNameResolver instanceNameResolver) {
        this.metadataResolver = Objects.requireNonNull(metadataResolver, "metadataResolver must not be null");
        // Обе C3-границы опциональны: частичный контекст (metadata-only) обязан работать
        // без них, просто без плана сценария.
        this.fetchPlanRegistry = fetchPlanRegistry;
        this.instanceNameResolver = instanceNameResolver;
    }

    /**
     * Итоговые пути: план сценария, расширенный дополнительными путями, уже углублённый.
     */
    public List<String> resolvePaths(Class<?> type, FetchScenario scenario,
                                     Collection<String> additionalPaths) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(scenario, "scenario must not be null");
        Collection<String> extras = additionalPaths == null ? List.of() : additionalPaths;
        if (fetchPlanRegistry != null) {
            return fetchPlanRegistry.pathsWith(type, scenario, extras);
        }
        if (extras.isEmpty()) {
            return List.of();
        }
        return FetchGraphs.deepen(type, extras, metadataResolver, instanceNameSource());
    }

    /** EntityGraph сценария; {@code null}, если грузить нечего. */
    public <T> EntityGraph<T> resolve(EntityManager entityManager, Class<T> type,
                                      FetchScenario scenario,
                                      Collection<String> additionalPaths) {
        List<String> paths = resolvePaths(type, scenario, additionalPaths);
        if (paths.isEmpty()) {
            return null;
        }
        return FetchGraphs.fromPaths(entityManager, type, paths);
    }

    private InstanceNameSource instanceNameSource() {
        return instanceNameResolver != null
            ? instanceNameResolver::instanceNamePaths
            : InstanceNameSource.NONE;
    }
}
