package org.ipro.metadata;

import jakarta.persistence.EntityGraph;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Subgraph;
import org.ipro.metadata.annotation.FieldType;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Общий хелпер построения EntityGraph — раньше одна и та же логика (взять
 * ENTITY_REFERENCE-поля грида → построить EntityGraph) была реализована дважды:
 * в AbstractBaseService (для обычных @EntityMetadata-сущностей) и отдельно в
 * GenericOwnedSectionService (для строк табличных частей). Теперь оба места
 * используют один и тот же код.
 */
public final class FetchGraphs {

    /** Максимальная глубина углубления fetch-путей через display-состав целей. */
    private static final int MAX_DEEPEN_DEPTH = 3;

    private FetchGraphs() {
    }

    /**
     * EntityGraph из явного списка JPA-путей (в т.ч. вложенных через точку —
     * "a.b" превращается в subgraph(a).addAttributeNodes(b)). null — если paths пуст.
     */
    public static <T> EntityGraph<T> fromPaths(EntityManager em, Class<T> rootClass, Collection<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return null;
        }
        EntityGraph<T> graph = em.createEntityGraph(rootClass);
        for (String path : paths) {
            String[] segments = path.split("\\.");
            if (segments.length == 1) {
                graph.addAttributeNodes(segments[0]);
            } else {
                Subgraph<?> subgraph = graph.addSubgraph(segments[0]);
                for (int i = 1; i < segments.length - 1; i++) {
                    subgraph = subgraph.addSubgraph(segments[i]);
                }
                subgraph.addAttributeNodes(segments[segments.length - 1]);
            }
        }
        return graph;
    }

    /** Дефолтный набор fetch-путей: имена ENTITY_REFERENCE-полей грида, без вложенности. */
    public static List<String> entityReferencePaths(List<FieldMetadataInfo> gridFields) {
        return gridFields.stream()
            .filter(f -> f.getResolvedType() == FieldType.ENTITY_REFERENCE)
            .map(FieldMetadataInfo::getName)
            .toList();
    }

    /**
     * Углубляет fetch-пути через display-состав целей ссылок. Рендер ссылочной колонки грида
     * вызывает {@code getDisplayName()} цели, а тот может читать собственные lazy-ассоциации
     * (например, {@code PrdSpec.getDisplayName()} читает {@code nomenclature}) — после закрытия
     * сессии такой прокси даёт LazyInitializationException. Этот метод добавляет к каждому пути,
     * заканчивающемуся (или проходящему через) ENTITY_REFERENCE-сегмент, вложенные пути из
     * состава отображаемого имени цели — единый {@code @InstanceName} для мигрированных
     * сущностей, иначе metadata-производный (selectColumns, fallback — displaySortFields) —
     * рекурсивно.
     *
     * Например: "prdSpecMtr" + selectColumns(PrdSpec) = [codeSpec, nomenclature.name]
     *   → добавляется "prdSpecMtr.nomenclature".
     *
     * Защиты: BFS с лимитом глубины (против циклов A→B→A), dedupe, try/catch на
     * resolver.resolve() — сущности без @EntityMetadata (например, legacy Workshop) просто
     * не углубляются.
     */
    public static List<String> deepen(Class<?> rootClass, Collection<String> fetchPaths,
                                      MetadataResolver metadataResolver) {
        return deepen(rootClass, fetchPaths, metadataResolver, InstanceNameSource.NONE);
    }

    /**
     * То же, но состав имени цели берётся из единого источника C3 для сущностей, объявивших
     * {@code @InstanceName}, и из metadata — для остальных. Без этого grid/list-канал
     * продолжал бы углубляться по {@code selectColumns}, а имя мигрированной сущности
     * вычислялось бы иначе, чем в lookup и аудите (ADX-09).
     */
    public static List<String> deepen(Class<?> rootClass, Collection<String> fetchPaths,
                                      MetadataResolver metadataResolver,
                                      InstanceNameSource instanceNameSource) {
        if (fetchPaths == null || fetchPaths.isEmpty() || metadataResolver == null) {
            return List.copyOf(fetchPaths == null ? List.of() : fetchPaths);
        }
        InstanceNameSource names = instanceNameSource == null ? InstanceNameSource.NONE : instanceNameSource;

        Set<String> result = new LinkedHashSet<>(fetchPaths);
        Deque<DeepenTask> queue = new ArrayDeque<>();

        for (String path : fetchPaths) {
            String[] segments = path.split("\\.");
            StringBuilder prefix = new StringBuilder();
            for (int i = 0; i < segments.length; i++) {
                if (i > 0) prefix.append('.');
                prefix.append(segments[i]);
                try {
                    ColumnPath column = ColumnPath.resolve(rootClass, prefix.toString());
                    if (column.getResolvedType() == FieldType.ENTITY_REFERENCE) {
                        queue.add(new DeepenTask(prefix.toString(), column.getJavaType(), 1));
                    }
                } catch (IllegalArgumentException invalidPath) {
                    // поле переименовали/удалили — пропускаем сегмент
                }
            }
        }

        while (!queue.isEmpty()) {
            DeepenTask task = queue.poll();
            if (task.depth() > MAX_DEEPEN_DEPTH) continue;

            List<ColumnPath> displayColumns = nameComposition(task.targetClass(), metadataResolver, names);

            for (ColumnPath display : displayColumns) {
                for (String nested : display.getFetchPaths()) {
                    String extended = task.prefix() + "." + nested;
                    if (result.add(extended) && display.getResolvedType() == FieldType.ENTITY_REFERENCE) {
                        queue.add(new DeepenTask(extended, display.getJavaType(), task.depth() + 1));
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    /**
     * Состав отображаемого имени цели: единый instance name для мигрированных сущностей,
     * иначе metadata-производный ({@code selectColumns} → {@code displaySortFields}).
     */
    private static List<ColumnPath> nameComposition(Class<?> targetClass, MetadataResolver metadataResolver,
                                                   InstanceNameSource instanceNameSource) {
        List<String> declared = instanceNameSource.pathsOf(targetClass);
        if (!declared.isEmpty()) {
            List<ColumnPath> resolved = new java.util.ArrayList<>(declared.size());
            for (String name : declared) {
                ColumnPath path = safeResolve(targetClass, name);
                if (path != null) {
                    resolved.add(path);
                }
            }
            return List.copyOf(resolved);
        }

        EntityMetadataInfo targetMeta;
        try {
            targetMeta = metadataResolver.resolve(targetClass);
        } catch (IllegalArgumentException notMetadataDriven) {
            return List.of();
        }
        List<ColumnPath> displayColumns = targetMeta.getSelectColumnPaths();
        if (displayColumns.isEmpty()) {
            displayColumns = targetMeta.getDisplaySortFields().stream()
                .map(name -> safeResolve(targetMeta.getEntityClass(), name))
                .filter(java.util.Objects::nonNull)
                .toList();
        }
        return displayColumns;
    }

    private static ColumnPath safeResolve(Class<?> rootClass, String path) {
        try {
            return ColumnPath.resolve(rootClass, path);
        } catch (IllegalArgumentException invalidPath) {
            return null;
        }
    }

    /** Один шаг BFS-обхода: путь + класс цели + глубина. */
    private record DeepenTask(String prefix, Class<?> targetClass, int depth) {}
}
