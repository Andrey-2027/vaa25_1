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
public final class CatalogTreeModel {

    private static final int MAX_DEPTH = QueryConstructorDraft.maxChainDepth();

    private CatalogTreeModel() { }

    /** Дерево «База данных»: все RLS-доступные сущности каталога. */
    public static List<ConstructorTreeNode> catalogRoots(List<QueryBuilderMetadataCatalog.Entity> roots) {
        List<ConstructorTreeNode> result = new ArrayList<>();
        if (roots == null) return result;
        for (var entity : roots) {
            result.add(entityNode(entity, 0, new LinkedHashSet<>(Set.of(entity.entityName())), roots));
        }
        return result;
    }

    public static List<ConstructorTreeNode> sourceRoots(List<QueryBuilderMetadataCatalog.Entity> roots,
            org.ipro.reportstudio.query.register.RegisterSliceReportTemplates recipes, String search) {
        var nodes = new ArrayList<ConstructorTreeNode>();
        for (var entity : roots) {
            if (entity.sectionOwner() != null) continue;
            var base = entityNode(entity, 0, new LinkedHashSet<>(Set.of(entity.entityName())), roots);
            var children = new ArrayList<>(base.children());
            for (var row : roots) if (row.sectionOwner() == entity.javaType()) children.add(
                entityNode(row, 0, new LinkedHashSet<>(Set.of(row.entityName())), roots));
            if ("Регистр".equals(entity.category())) {
                var registerSources = new ArrayList<ConstructorTreeNode>();
                registerSources.add(new ConstructorTreeNode(ConstructorTreeNode.Kind.ENTITY, "Записи регистра", entity, null, null, children));
                if (recipes != null && recipes.sliceSources().stream().anyMatch(source -> source.javaType() == entity.javaType())) {
                    try {
                        var fields = recipes.sliceFields(entity.javaType()).stream().map(field -> new ConstructorTreeNode(
                            ConstructorTreeNode.Kind.PROPERTY, field.name(), entity, field, null, List.of())).toList();
                        registerSources.add(new ConstructorTreeNode(ConstructorTreeNode.Kind.SLICE, "Срез последних", entity, null, null, fields));
                    } catch (IllegalArgumentException unsupported) { /* Ordinary rows remain usable. */ }
                }
                children = registerSources;
            }
            nodes.add(new ConstructorTreeNode(ConstructorTreeNode.Kind.ENTITY, entity.entityName(), entity, null, null, children));
        }
        var groups = new ArrayList<ConstructorTreeNode>();
        for (var group : List.of("Справочники", "Документы", "Регистры", "Прочее")) {
            String category = switch (group) { case "Справочники" -> "Справочник"; case "Документы" -> "Документ"; case "Регистры" -> "Регистр"; default -> "Прочее"; };
            var members = nodes.stream().filter(node -> category.equals(node.entity().category()))
                .sorted(java.util.Comparator.comparing(node -> node.entity().caption())).map(node -> filter(node, search)).filter(java.util.Objects::nonNull).toList();
            if (!members.isEmpty()) groups.add(new ConstructorTreeNode(ConstructorTreeNode.Kind.GROUP, group, null, null, null, members));
        }
        return groups;
    }

    public static ConstructorTreeNode filter(ConstructorTreeNode node, String text) {
        String needle = text == null ? "" : text.strip().toLowerCase(java.util.Locale.ROOT);
        if (needle.isEmpty() || (label(node) + " " + name(node)).toLowerCase(java.util.Locale.ROOT).contains(needle)) return node;
        var children = node.children().stream().map(child -> filter(child, text)).filter(java.util.Objects::nonNull).toList();
        return children.isEmpty() ? null : new ConstructorTreeNode(node.kind(), node.caption(), node.entity(), node.field(), node.association(), children, node.tableAlias());
    }

    public static String label(ConstructorTreeNode node) {
        if (node.kind() == ConstructorTreeNode.Kind.GROUP || node.kind() == ConstructorTreeNode.Kind.SLICE || "Записи регистра".equals(node.caption())) return node.caption();
        if (node.field() != null) return node.field().caption();
        if (node.association() != null) return node.association().caption();
        return node.entity() == null ? node.caption() : node.entity().caption();
    }
    public static String name(ConstructorTreeNode node) {
        if (node.kind() == ConstructorTreeNode.Kind.GROUP) return "";
        if (node.field() != null) return node.field().name();
        if (node.association() != null) return node.association().name();
        return node.entity() == null ? "" : node.entity().entityName();
    }
    public static String category(ConstructorTreeNode node) {
        return node.kind() == ConstructorTreeNode.Kind.GROUP ? "" : node.kind() == ConstructorTreeNode.Kind.SLICE ? "Срез последних"
            : node.kind() == ConstructorTreeNode.Kind.CTE ? "Источник запроса" : node.field() != null || node.association() != null ? "" : node.entity().category();
    }
    public static String dataType(ConstructorTreeNode node) {
        return node.field() != null ? (node.field().referenceType() == null ? node.field().javaType() : node.field().referenceType()).getSimpleName() : node.entity() == null ? "" : node.entity().javaType().getSimpleName();
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
        return new ConstructorTreeNode(ConstructorTreeNode.Kind.ENTITY,
                table.entity().javaType() == Object.class ? table.entity().caption() : table.entity().entityName(),
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
            children.add(new ConstructorTreeNode(ConstructorTreeNode.Kind.PROPERTY,
                    entity.javaType() == Object.class ? field.caption() : field.name(),
                    entity, field, null, List.of()));
        }
        if (org.ipro.register.RecordedRegisterEntry.class.isAssignableFrom(entity.javaType()) || entity.fields().stream().anyMatch(field -> field.name().equals("recorder_id")))
            children.add(new ConstructorTreeNode(ConstructorTreeNode.Kind.PROPERTY, "Регистратор", entity,
                new QueryBuilderMetadataCatalog.Field("recorder", "Регистратор", org.ipro.register.RecorderRef.class, false), null, List.of()));
        // Идентификатор записи (id — PK BaseEntity): в метаданных не публикуется,
        // но выбору доступен — нужен для привязки/открытия строк отчёта.
        if (entity.javaType() != Object.class) children.add(new ConstructorTreeNode(ConstructorTreeNode.Kind.PROPERTY, "Идентификатор записи (id)",
                entity, new QueryBuilderMetadataCatalog.Field("id", "Идентификатор записи", Long.class, true),
                null, List.of()));
        if (depth < MAX_DEPTH) {
            for (var association : entity.associations()) {
                var target = targetOf(association, roots);
                if (target == null || visited.contains(target.entityName())) {
                    children.add(new ConstructorTreeNode(ConstructorTreeNode.Kind.ASSOCIATION, association.caption(), entity, null, association, List.of()));
                    continue;
                }
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
