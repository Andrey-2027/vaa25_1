package org.ipro.query;

import org.ipro.filtergrid.filter.FilterDataType;
import org.ipro.filtergrid.filter.FilterFieldResolver;

import java.util.List;
import java.util.Objects;

/** Адаптер полей визуального запроса для общего FilterTreeEditor. */
public final class VisualQueryFilterResolver implements FilterFieldResolver {
    private final List<ResolvedFilterField> fields;

    /** Таблица черновика конструктора: alias + сущность каталога. */
    public record TableAlias(String alias, QueryBuilderMetadataCatalog.Entity entity) { }

    /**
     * Резолвер по таблицам черновика (до сборки definition): поля всех выбранных
     * таблиц, включая независимые JOIN — как в 1С, WHERE доступен сразу после
     * выбора таблиц, без обязательных полей SELECT.
     */
    public VisualQueryFilterResolver(List<TableAlias> tables) {
        Objects.requireNonNull(tables, "tables");
        var result = new java.util.ArrayList<ResolvedFilterField>();
        for (TableAlias table : tables) {
            if (table.entity() == null) continue;
            addFields(table.alias(), table.entity(), result);
        }
        fields = List.copyOf(result);
    }

    /** Резолвер для этапа пакета: реальные поля плюс доступные предыдущие CTE. */
    public VisualQueryFilterResolver(VisualQueryDefinition definition,
                                     QueryBuilderMetadataCatalog catalog,
                                     VisualQueryPackage.VirtualCatalog virtualCatalog) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(virtualCatalog, "virtualCatalog");
        var result = new java.util.ArrayList<ResolvedFilterField>();
        QueryBuilderMetadataCatalog.Entity root = virtualCatalog.entity(definition.entityName());
        if (root == null) root = catalog.root(definition.entityName());
        if (root == null) throw new IllegalArgumentException("Источник запроса не найден: " + definition.entityName());
        addFields(definition.entityAlias(), root, result);
        // Поля JOIN-целей (как в 1С: WHERE доступен по всем выбранным таблицам):
        // связанные — по ассоциациям корня, независимые — предыдущий CTE или сущность каталога.
        for (var join : definition.joins()) {
            var target = joinTargetEntity(join, root, catalog, virtualCatalog);
            if (target != null) addFields(join.alias(), target, result);
        }
        fields = List.copyOf(result);
    }

    private static QueryBuilderMetadataCatalog.Entity joinTargetEntity(VisualQueryDefinition.Join join,
                                                                       QueryBuilderMetadataCatalog.Entity root,
                                                                       QueryBuilderMetadataCatalog catalog,
                                                                       VisualQueryPackage.VirtualCatalog virtualCatalog) {
        QueryBuilderMetadataCatalog.Entity virtual = virtualCatalog.entity(join.sourcePath());
        if (virtual != null) return virtual;
        if (join.independent()) {
            try { return catalog.root(join.sourcePath()); } catch (RuntimeException unknown) { return null; }
        }
        String association = join.sourcePath().substring(join.sourcePath().lastIndexOf('.') + 1);
        var target = root.associations().stream().filter(a -> a.name().equals(association)).findFirst().orElse(null);
        if (target == null || target.targetType() == null) return null;
        return catalog.roots().stream().filter(e -> e.javaType().equals(target.targetType())).findFirst().orElse(null);
    }

    public VisualQueryFilterResolver(VisualQueryDefinition definition,
                                     QueryBuilderMetadataCatalog catalog) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(catalog, "catalog");
        var root = catalog.root(definition.entityName());
        var result = new java.util.ArrayList<ResolvedFilterField>();
        addFields(definition.entityAlias(), root, result);
        for (var join : definition.joins()) {
            String alias = join.alias();
            String association = join.sourcePath().substring(join.sourcePath().lastIndexOf('.') + 1);
            var target = root.associations().stream().filter(a -> a.name().equals(association)).findFirst().orElse(null);
            if (target != null) catalog.roots().stream().filter(e -> e.javaType().equals(target.targetType())).findFirst()
                    .ifPresent(entity -> addFields(alias, entity, result));
        }
        fields = List.copyOf(result);
    }

    private static ResolvedFilterField resolveField(String alias, QueryBuilderMetadataCatalog.Field field) {
        return new ResolvedFilterField(alias + "." + field.name(), field.caption(), field.javaType(), dataType(field.javaType()), true);
    }

    private static void addFields(String alias, QueryBuilderMetadataCatalog.Entity entity, List<ResolvedFilterField> result) {
        // Recorder identity and ordering are available as projection data, but a generic
        // report filter must not silently acquire recorder-history read semantics.
        entity.fields().stream().filter(field -> !field.technical())
                .forEach(field -> result.add(resolveField(alias, field)));
        entity.associations().stream().filter(association -> association.joinType() == QueryBuilderMetadataCatalog.JoinType.TO_ONE
                && association.targetType() != null).forEach(association -> result.add(new ResolvedFilterField(
                    alias + "." + association.name(), association.caption(), association.targetType(), FilterDataType.ENTITY_REFERENCE, true)));
        // Align WHERE with the existing compiler SELECT/ON technical-id contract.
        // Virtual CTEs have only projected columns; never invent an id on a virtual source.
        if (entity.javaType() != Object.class && entity.fields().stream().noneMatch(field -> field.name().equals("id")))
            result.add(resolveField(alias, new QueryBuilderMetadataCatalog.Field("id", "Идентификатор записи", Long.class, true)));
    }

    @Override public ResolvedFilterField resolve(String path) {
        return fields.stream().filter(f -> f.path().equals(path)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Поле визуального запроса не найдено: " + path));
    }
    @Override public List<ResolvedFilterField> fields() { return fields; }
    @Override public List<?> valueOptions(ResolvedFilterField field) {
        return field != null && field.javaType().isEnum() ? List.of(field.javaType().getEnumConstants()) : List.of();
    }
    private static FilterDataType dataType(Class<?> type) {
        if (type.isEnum()) return FilterDataType.ENUM;
        if (type == Boolean.class || type == boolean.class) return FilterDataType.BOOLEAN;
        if (Number.class.isAssignableFrom(type) || type.isPrimitive()) return FilterDataType.NUMBER;
        if (java.time.temporal.Temporal.class.isAssignableFrom(type)) return FilterDataType.DATE;
        return FilterDataType.TEXT;
    }
}
