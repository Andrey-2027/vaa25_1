package org.ipro.data;

import jakarta.persistence.EntityGraph;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Subgraph;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.ManagedType;
import jakarta.persistence.metamodel.SingularAttribute;
import org.ipro.fetch.instance.InstanceNameResolver;
import org.ipro.fetch.plan.FetchPlanRegistry;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.metadata.FetchGraphs;
import org.ipro.metadata.InstanceNameSource;
import org.ipro.metadata.MetadataResolver;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * Построение явного EntityGraph для REST API с проверками по persistence metamodel (ADR-0007 §4.1, F-REST-READ-3).
     *
     * <p>Последовательность:
     * maximum resolved source paths + mandatory root id
     *   -> validate metamodel/profile -> deduplicate and merge path prefixes
     *   -> explicit root attribute nodes and to-one subgraphs -> fetchgraph</p>
     */
    public <T> EntityGraph<T> resolveRestFetchGraph(EntityManager entityManager, Class<T> type,
                                                    Collection<String> fixedFetchPaths) {
        Objects.requireNonNull(entityManager, "entityManager must not be null");
        Objects.requireNonNull(type, "type must not be null");
        if (fixedFetchPaths == null || fixedFetchPaths.isEmpty()) {
            throw new RestGraphException(RestGraphException.Code.MISSING_PATHS, type, null,
                "Fixed fetch profile must not be null or empty");
        }

        EntityType<T> entityType;
        try {
            entityType = entityManager.getMetamodel().entity(type);
        } catch (IllegalArgumentException e) {
            throw new RestGraphException(RestGraphException.Code.INVALID_PERSISTENT_PATH, type, null,
                "Type is not managed by JPA metamodel: " + type.getName());
        }

        // 1. Поиск фактического primary identifier атрибута сущности
        String idAttributeName = findIdAttributeName(entityType);
        if (!fixedFetchPaths.contains(idAttributeName)) {
            throw new RestGraphException(RestGraphException.Code.MISSING_IDENTIFIER_PATH, type, idAttributeName,
                "Fixed fetch profile must contain primary identifier attribute '" + idAttributeName + "'");
        }

        // 2. Валидация путей по метамодели: запрет голых ассоциаций и коллекций
        EntityGraph<T> graph = entityManager.createEntityGraph(type);
        Map<String, Subgraph<?>> subgraphs = new HashMap<>();

        List<String> sortedPaths = fixedFetchPaths.stream().distinct().sorted().toList();

        for (String path : sortedPaths) {
            if (path.isBlank()) {
                throw new RestGraphException(RestGraphException.Code.INVALID_PERSISTENT_PATH, type, path,
                    "Empty or blank path in fixed fetch profile");
            }
            String[] segments = path.split("\\.");
            ManagedType<?> currentType = entityType;
            for (int i = 0; i < segments.length; i++) {
                String segment = segments[i];
                Attribute<?, ?> attr;
                try {
                    attr = currentType.getAttribute(segment);
                } catch (IllegalArgumentException e) {
                    throw new RestGraphException(RestGraphException.Code.INVALID_PERSISTENT_PATH, type, path,
                        "Attribute '" + segment + "' not found on type " + currentType.getJavaType().getName());
                }

                if (attr.isCollection()) {
                    throw new RestGraphException(RestGraphException.Code.COLLECTION_PATH_NOT_SUPPORTED, type, path,
                        "Plural/collection attribute '" + segment + "' is not supported in REST fetch profile");
                }

                boolean isLast = (i == segments.length - 1);
                if (attr.isAssociation()) {
                    if (isLast) {
                        throw new RestGraphException(RestGraphException.Code.BARE_ASSOCIATION_NOT_ALLOWED, type, path,
                            "Bare association '" + segment + "' without terminal attribute is forbidden in REST fetch profile");
                    }
                    if (attr instanceof SingularAttribute<?, ?> singular) {
                        if (singular.getType() instanceof ManagedType<?> managedTarget) {
                            currentType = managedTarget;
                        } else {
                            throw new RestGraphException(RestGraphException.Code.INVALID_PERSISTENT_PATH, type, path,
                                "Association target is not a managed type: " + segment);
                        }
                    }
                }
            }

            // 3. Построение графа с явными subgraphs и слиянием общих префиксов
            if (segments.length == 1) {
                graph.addAttributeNodes(segments[0]);
            } else {
                StringBuilder prefix = new StringBuilder();
                Subgraph<?> parentSubgraph = null;
                for (int i = 0; i < segments.length - 1; i++) {
                    String seg = segments[i];
                    if (prefix.length() > 0) prefix.append('.');
                    prefix.append(seg);
                    String fullPrefix = prefix.toString();

                    if (i == 0) {
                        parentSubgraph = subgraphs.computeIfAbsent(fullPrefix, k -> graph.addSubgraph(seg));
                    } else {
                        final Subgraph<?> prevParent = parentSubgraph;
                        parentSubgraph = subgraphs.computeIfAbsent(fullPrefix, k -> prevParent.addSubgraph(seg));
                    }
                }
                parentSubgraph.addAttributeNodes(segments[segments.length - 1]);
            }
        }

        return graph;
    }

    private static String findIdAttributeName(EntityType<?> entityType) {
        for (SingularAttribute<?, ?> attr : entityType.getSingularAttributes()) {
            if (attr.isId()) {
                return attr.getName();
            }
        }
        try {
            return entityType.getId(entityType.getIdType().getJavaType()).getName();
        } catch (Exception ignored) {
            return "id";
        }
    }

    private InstanceNameSource instanceNameSource() {
        return instanceNameResolver != null
            ? instanceNameResolver::instanceNamePaths
            : InstanceNameSource.NONE;
    }
}
