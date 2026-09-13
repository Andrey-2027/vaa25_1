package org.ipro.fetch.plan;

import jakarta.persistence.EntityGraph;
import jakarta.persistence.EntityManager;
import org.ipro.fetch.ManagedEntityTypes;
import org.ipro.fetch.instance.InstanceNameResolver;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.FetchGraphs;
import org.ipro.metadata.FieldMetadataInfo;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.RowMetadataInfo;
import org.ipro.metadata.annotation.FieldType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Единый источник fetch-графа для пары (entityClass, scenario) — C3.3, см. ADR-0006.
 *
 * <p>Заменяет ручной расчёт путей в потребителях: план выводится из эффективной metadata,
 * к нему добавляются зависимости имени сущности ({@code @InstanceName}) и объявленные
 * зависимости сценария выбора ({@code @Lookup.fetch}), а затем граф углубляется ровно
 * настолько, чтобы имя ссылочных целей читалось без lazy load. Прикладной код больше не
 * задаёт глубину и не перечисляет association paths вручную (ADX-07).</p>
 *
 * <p>Правила (C3.3):</p>
 * <ol>
 * <li>стандартный план выводится из metadata — не из кода потребителя;</li>
 * <li>зависимости instance name включаются в {@code LOOKUP} и {@code DETAIL};</li>
 * <li>объявленные зависимости выбора включаются в {@code LOOKUP} цели;</li>
 * <li>неизвестный объявленный путь — отказ старта, а не тихий runtime fallback;</li>
 * <li>планы кэшируются и не смешиваются между сценариями и сущностями.</li>
 * </ol>
 *
 * <p>Ограничения: углубление идёт BFS с лимитом глубины и защитой от циклов
 * ({@link FetchGraphs#deepen}), порядок путей детерминирован (порядок вставки), а размер
 * графа и причина каждого пути доступны через {@link FetchPlan}. Это internal-компонент C3:
 * публичный контракт приложения — модель и metadata, а не этот registry.</p>
 */
public final class FetchPlanRegistry {

    private final MetadataResolver metadataResolver;
    private final InstanceNameResolver instanceNameResolver;
    private final Map<Class<?>, List<LookupDependency>> lookupDependencies;
    private final Map<PlanKey, FetchPlan> cache = new ConcurrentHashMap<>();

    public FetchPlanRegistry(ManagedEntityTypes managedEntityTypes,
                             MetadataResolver metadataResolver,
                             InstanceNameResolver instanceNameResolver) {
        this(Objects.requireNonNull(managedEntityTypes, "managedEntityTypes must not be null").all(),
            metadataResolver, instanceNameResolver);
    }

    /**
     * Тот же registry над явным набором классов — используется в тестах, чтобы проверить
     * валидацию объявлений и разделение сценариев на произвольных фикстурах, не поднимая
     * persistence unit.
     */
    FetchPlanRegistry(Collection<Class<?>> managedEntityTypes,
                      MetadataResolver metadataResolver,
                      InstanceNameResolver instanceNameResolver) {
        this.metadataResolver = Objects.requireNonNull(metadataResolver, "metadataResolver must not be null");
        this.instanceNameResolver = Objects.requireNonNull(instanceNameResolver,
            "instanceNameResolver must not be null");
        List<Class<?>> orderedTypes = Objects.requireNonNull(managedEntityTypes,
                "managedEntityTypes must not be null").stream()
            .sorted(Comparator.comparing(Class::getName))
            .toList();
        this.lookupDependencies = collectLookupDependencies(orderedTypes);
    }

    /** План для пары (класс, сценарий); результат кэшируется. */
    public FetchPlan plan(Class<?> entityClass, FetchScenario scenario) {
        Objects.requireNonNull(entityClass, "entityClass must not be null");
        Objects.requireNonNull(scenario, "scenario must not be null");
        return cache.computeIfAbsent(new PlanKey(entityClass, scenario), key -> compute(key));
    }

    /** Пути ассоциаций плана — то, что передаётся в запрос. */
    public List<String> paths(Class<?> entityClass, FetchScenario scenario) {
        return plan(entityClass, scenario).paths();
    }

    /**
     * EntityGraph сценария или {@code null}, если плану нечего загружать.
     */
    public <T> EntityGraph<T> entityGraph(EntityManager entityManager, Class<T> entityClass,
                                          FetchScenario scenario) {
        Collection<String> paths = paths(entityClass, scenario);
        if (paths.isEmpty()) {
            return null;
        }
        return FetchGraphs.fromPaths(entityManager, entityClass, paths);
    }

    // ---------------------------------------------------------------- resolution

    private FetchPlan compute(PlanKey key) {
        Class<?> entityClass = key.entityClass();
        FetchScenario scenario = key.scenario();

        Map<String, String> included = new LinkedHashMap<>();
        for (String path : basePaths(entityClass, scenario)) {
            included.putIfAbsent(path, "metadata:" + scenario);
        }

        // C3.1: имя сущности рендерится в форме и в выборе — его связи обязаны быть загружены.
        if (scenario == FetchScenario.LOOKUP || scenario == FetchScenario.DETAIL) {
            for (String path : instanceNameResolver.instanceNameFetchPaths(entityClass)) {
                included.putIfAbsent(path, "instance-name");
            }
        }

        // Зависимости сценария выбора, объявленные на ссылающихся полях.
        if (scenario == FetchScenario.LOOKUP) {
            for (LookupDependency dependency : lookupDependencies.getOrDefault(entityClass, List.of())) {
                for (String path : dependency.paths()) {
                    included.putIfAbsent(path, dependency.reason());
                }
            }
        }

        // Углубление через единое имя ссылочных целей: ссылочная колонка/поле рендерит имя цели,
        // а имя может читать собственные связи.
        List<String> deepened = FetchGraphs.deepen(entityClass, included.keySet(), metadataResolver,
            instanceNameResolver::instanceNamePaths);
        for (String path : deepened) {
            int lastDot = path.lastIndexOf('.');
            included.putIfAbsent(path, lastDot < 0
                ? "reference-name"
                : "reference-name:" + path.substring(0, lastDot));
        }

        List<String> orderedPaths = included.keySet().stream().sorted().toList();
        Map<String, String> orderedReasons = new LinkedHashMap<>();
        for (String path : orderedPaths) {
            orderedReasons.put(path, included.get(path));
        }
        return new FetchPlan(entityClass, scenario, orderedPaths, orderedReasons);
    }

    /** Базовый набор путей сценария из эффективной metadata. */
    private List<String> basePaths(Class<?> entityClass, FetchScenario scenario) {
        return switch (scenario) {
            case LIST -> entityReferencePaths(gridFieldsOf(entityClass));
            case DETAIL -> entityReferencePaths(formFieldsOf(entityClass));
            case LOOKUP -> selectColumnFetchPaths(entityClass);
            case ROW -> entityReferencePaths(rowGridFieldsOf(entityClass));
        };
    }

    private List<FieldMetadataInfo> gridFieldsOf(Class<?> entityClass) {
        EntityMetadataInfo meta = metadataOrNull(entityClass);
        return meta == null ? List.of() : meta.getGridFields();
    }

    private List<FieldMetadataInfo> formFieldsOf(Class<?> entityClass) {
        EntityMetadataInfo meta = metadataOrNull(entityClass);
        return meta == null ? List.of() : meta.getFormFields();
    }

    private List<FieldMetadataInfo> rowGridFieldsOf(Class<?> entityClass) {
        RowMetadataInfo row = metadataResolver.resolveRowMetadata(entityClass);
        return row == null ? List.of() : row.getGridFields();
    }

    private List<String> selectColumnFetchPaths(Class<?> entityClass) {
        EntityMetadataInfo meta = metadataOrNull(entityClass);
        if (meta == null) {
            return List.of();
        }
        TreeSet<String> result = new TreeSet<>();
        for (ColumnPath path : meta.getSelectColumnPaths()) {
            for (String fetchPath : path.getFetchPaths()) {
                result.add(fetchPath);
            }
        }
        return List.copyOf(result);
    }

    private EntityMetadataInfo metadataOrNull(Class<?> entityClass) {
        try {
            return metadataResolver.resolve(entityClass);
        } catch (IllegalArgumentException notMetadataDriven) {
            return null;
        }
    }

    private static List<String> entityReferencePaths(List<FieldMetadataInfo> fields) {
        return fields.stream()
            .filter(f -> f.getResolvedType() == FieldType.ENTITY_REFERENCE)
            .map(FieldMetadataInfo::getName)
            .sorted()
            .toList();
    }

    // -------------------------------------------------------------- declarations

    /**
     * Собирает и валидирует объявленные зависимости выбора ({@code @Lookup.fetch}).
     * Валидация здесь, а не при первом запросе: неизвестный путь — ошибка конфигурации
     * приложения и должна падать на старте.
     */
    private Map<Class<?>, List<LookupDependency>> collectLookupDependencies(Collection<Class<?>> managedTypes) {
        Map<Class<?>, List<LookupDependency>> collected = new LinkedHashMap<>();
        for (Class<?> type : managedTypes.stream().sorted(Comparator.comparing(Class::getName)).toList()) {
            EntityMetadataInfo meta = metadataOrNull(type);
            if (meta == null) {
                continue;
            }
            for (FieldMetadataInfo field : meta.getAllAnnotatedFields().stream()
                    .sorted(Comparator.comparing(FieldMetadataInfo::getName)).toList()) {
                if (!field.hasLookup()) {
                    continue;
                }
                String[] declared = field.getLookupFetch();
                if (declared.length == 0) {
                    continue;
                }
                Class<?> target = field.getLookupEntity();
                String reason = "lookup:" + type.getSimpleName() + "." + field.getName();
                List<String> validated = new ArrayList<>(declared.length);
                for (String path : declared) {
                    if (path == null || path.isBlank()) {
                        throw new IllegalStateException("@Lookup(fetch) on " + type.getName() + "."
                            + field.getName() + " declares a blank attribute path");
                    }
                    try {
                        ColumnPath.resolve(target, path);
                    } catch (IllegalArgumentException invalidPath) {
                        throw new IllegalStateException("@Lookup(fetch) on " + type.getName() + "."
                            + field.getName() + " declares invalid attribute path '" + path
                            + "' of " + target.getName(), invalidPath);
                    }
                    validated.add(path);
                }
                validated.sort(String::compareTo);
                collected.computeIfAbsent(target, key -> new ArrayList<>())
                    .add(new LookupDependency(reason, List.copyOf(validated)));
            }
        }
        Map<Class<?>, List<LookupDependency>> result = new LinkedHashMap<>();
        collected.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return Map.copyOf(result);
    }

    /** Объявленные пути для одной сущности-цели + диагностируемая причина. */
    private record LookupDependency(String reason, List<String> paths) {
    }

    /** Ключ кэша планов. */
    private record PlanKey(Class<?> entityClass, FetchScenario scenario) {
    }
}
