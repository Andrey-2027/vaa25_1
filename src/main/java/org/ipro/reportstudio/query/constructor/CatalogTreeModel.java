package org.ipro.reportstudio.query.constructor;

import org.ipro.reportstudio.query.QueryBuilderMetadataCatalog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Построение деревьев каталога для вкладок конструктора: «База данных»
 * (все сущности) и «поля выбранных таблиц». Вложенность ассоциаций
 * ограничена глубиной, циклы по сущностям отсекаются по пути.
 */
final class CatalogTreeModel {

    private static final int MAX_DEPTH = QueryConstructorDraft.maxChainDepth();

    private CatalogTreeModel() { }

    /** Дерево «База данных»: все RLS-доступные сущности каталога. */
    static List<ConstructorTreeNode> catalogRoots(List<QueryBuilderMetadataCatalog.Entity> roots) {
        List<ConstructorTreeNode> result = new ArrayList<>();
        if (roots == null) return result;
        for (var entity : roots) {
            result.add(entityNode(entity, 0, new LinkedHashSet<>(Set.of(entity.entityName())), roots));
        }
        return result;
    }

    /** Дерево полей выбранных таблиц (узлы-таблицы несут alias для второго столбца). */
    static List<ConstructorTreeNode> tableRoots(List<QueryConstructorDraft.TableRef> tables,
                                                List<QueryBuilderMetadataCatalog.Entity> roots) {
        List<ConstructorTreeNode> result = new ArrayList<>();
        if (tables == null) return result;
        for (var table : tables) {
            result.add(tableNode(table, roots));
        }
        return result;
    }

    /** Узел-таблица для выбранной таблицы черновика. */
    static ConstructorTreeNode tableNode(QueryConstructorDraft.TableRef table,
                                         List<QueryBuilderMetadataCatalog.Entity> roots) {
        List<ConstructorTreeNode> children = childrenOf(table.entity(), 0,
                new LinkedHashSet<>(Set.of(table.entity().entityName())), roots);
        return new ConstructorTreeNode(ConstructorTreeNode.Kind.ENTITY, table.entity().entityName(),
                table.entity(), null, null, children, table.alias());
    }

    private static ConstructorTreeNode entityNode(QueryBuilderMetadataCatalog.Entity entity, int depth,
                                                  Set<String> visited,
                                                  List<QueryBuilderMetadataCatalog.Entity> roots) {
        return new ConstructorTreeNode(ConstructorTreeNode.Kind.ENTITY, entity.entityName(), entity,
                null, null, childrenOf(entity, depth, visited, roots));
    }

    private static List<ConstructorTreeNode> childrenOf(QueryBuilderMetadataCatalog.Entity entity, int depth,
                                                        Set<String> visited,
                                                        List<QueryBuilderMetadataCatalog.Entity> roots) {
        List<ConstructorTreeNode> children = new ArrayList<>();
        for (var field : entity.fields()) {
            children.add(new ConstructorTreeNode(ConstructorTreeNode.Kind.PROPERTY, field.name(),
                    entity, field, null, List.of()));
        }
        // Идентификатор записи (id — PK BaseEntity): в метаданных не публикуется,
        // но выбору доступен — нужен для привязки/открытия строк отчёта.
        children.add(new ConstructorTreeNode(ConstructorTreeNode.Kind.PROPERTY, "Идентификатор записи (id)",
                entity, new QueryBuilderMetadataCatalog.Field("id", "Идентификатор записи", Long.class, true),
                null, List.of()));
        if (depth < MAX_DEPTH) {
            for (var association : entity.associations()) {
                var target = targetOf(association, roots);
                if (target == null || visited.contains(target.entityName())) continue;
                Set<String> childVisited = new LinkedHashSet<>(visited);
                childVisited.add(target.entityName());
                children.add(associationNode(entity, association, target, depth + 1, childVisited, roots));
            }
        }
        return children;
    }

    /** Узел ассоциации: entity = целевая сущность, children = её поля и вложенные ассоциации. */
    private static ConstructorTreeNode associationNode(QueryBuilderMetadataCatalog.Entity source,
                                                       QueryBuilderMetadataCatalog.Association association,
                                                       QueryBuilderMetadataCatalog.Entity target,
                                                       int depth, Set<String> visited,
                                                       List<QueryBuilderMetadataCatalog.Entity> roots) {
        return new ConstructorTreeNode(ConstructorTreeNode.Kind.ASSOCIATION, association.name(), target,
                null, association, childrenOf(target, depth, visited, roots));
    }

    private static QueryBuilderMetadataCatalog.Entity targetOf(QueryBuilderMetadataCatalog.Association association,
                                                               List<QueryBuilderMetadataCatalog.Entity> roots) {
        if (association.targetType() == null) return null;
        return roots.stream()
                .filter(e -> e.javaType().equals(association.targetType()))
                .findFirst().orElse(null);
    }
}
