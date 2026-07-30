package org.ip.metadata;

import jakarta.persistence.EntityGraph;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Subgraph;
import org.ip.metadata.annotation.FieldType;

import java.util.Collection;
import java.util.List;

/**
 * Общий хелпер построения EntityGraph — раньше одна и та же логика (взять
 * ENTITY_REFERENCE-поля грида → построить EntityGraph) была реализована дважды:
 * в AbstractBaseService (для обычных @EntityMetadata-сущностей) и отдельно в
 * AbstractTableSectionService (для строк табличных частей). Теперь оба места
 * используют один и тот же код.
 */
public final class FetchGraphs {

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
}
