package org.ipro.query.ui.list;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.ipro.metadata.annotation.TableSectionMetadata;
import org.ipro.query.*;
import java.util.*;

/** Preaggregates query output by a selected key; table sections have a convenience factory. */
public final class ListCteGenerator {
    private final QueryBuilderMetadataCatalog catalog;
    public ListCteGenerator(QueryBuilderMetadataCatalog catalog) { this.catalog = Objects.requireNonNull(catalog); }

    public ConstructorList generate(VisualQueryPackage source, String keyColumn, String valueColumn,
                                    String separator, boolean distinct, VisualQueryOrder.Direction direction, Set<String> occupied) {
        var names = new HashSet<>(occupied);
        source.ctes().forEach(c -> names.add(c.name()));
        catalog.roots().forEach(e -> names.add(e.entityName()));
        String name = "list1";
        for (int n = 1; names.contains(name) || names.contains(name + "_rows"); n++) name = "list" + (n + 1);
        if (source.ctes().stream().anyMatch(c -> occupied.contains(c.name())))
            throw new IllegalArgumentException("Имена этапов источника списка уже заняты");
        return generateNamed(name, source, keyColumn, valueColumn, separator, distinct, direction);
    }

    public ConstructorList generateNamed(String name, VisualQueryPackage source, String keyColumn, String valueColumn,
                                         String separator, boolean distinct, VisualQueryOrder.Direction direction) {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*") || "main".equals(name))
            throw new IllegalArgumentException("Некорректное имя списка");
        VisualQueryCompiler.compile(source, catalog);
        String rowsName = name + "_rows";
        if (catalog.roots().stream().anyMatch(e -> e.entityName().equals(name) || e.entityName().equals(rowsName)))
            throw new IllegalArgumentException("Имя списка конфликтует с сущностью");
        var virtual = VisualQueryPackage.VirtualCatalog.empty();
        for (var cte : source.ctes()) {
            if (cte.name().equals(name) || cte.name().equals(rowsName)
                    || catalog.roots().stream().anyMatch(e -> e.entityName().equals(cte.name())))
                throw new IllegalArgumentException("Конфликт имени этапа источника списка");
            virtual = virtual.add(cte.name(), cte.definition(), catalog);
        }
        var output = VisualQueryPackage.VirtualEntity.from(rowsName, source.main(), catalog, virtual).asCatalogEntity();
        var key = output.fields().stream().filter(f -> f.name().equals(keyColumn)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Не найдена колонка ключа списка"));
        if (key.javaType() == Object.class || key.javaType().isAnnotationPresent(jakarta.persistence.Entity.class))
            throw new IllegalArgumentException("Ключ списка должен иметь известный скалярный тип");
        var value = output.fields().stream().filter(f -> f.name().equals(valueColumn) && f.javaType() == String.class).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Значение списка должно быть текстовой колонкой"));
        var aggregate = new VisualQueryDefinition.Aggregate("LISTAGG", "listRows." + valueColumn, "value",
                separator, distinct, null, direction);
        var definition = new VisualQueryDefinition(VisualQueryDefinition.CURRENT_VERSION, rowsName, "listRows",
                List.of(new VisualQueryDefinition.SelectField("listRows." + keyColumn, "key")), List.of(),
                List.of("listRows." + keyColumn), List.of(aggregate), null);
        var stages = new ArrayList<>(source.ctes());
        stages.add(new VisualQueryPackage.Cte(rowsName, source.main()));
        stages.add(new VisualQueryPackage.Cte(name, new VisualQueryPackage.CteSource("listRows", rowsName), definition));
        var entity = new QueryBuilderMetadataCatalog.Entity(name, Object.class, List.of(
                new QueryBuilderMetadataCatalog.Field("key", key.caption(), key.javaType(), true, List.of(), key.referenceType()),
                new QueryBuilderMetadataCatalog.Field("value", value.caption(), String.class, false)), List.of(), "Список: " + value.caption());
        try {
            var mapper = new ObjectMapper(); var settings = mapper.createObjectNode();
            settings.put("version", 2); settings.put("name", name);
            settings.set("sourceQuery", mapper.readTree(new VisualQueryPackageJsonCodec().write(source)));
            settings.put("keyColumn", keyColumn); settings.put("valueColumn", valueColumn);
            settings.put("separator", aggregate.separator()); settings.put("distinct", distinct);
            settings.put("direction", aggregate.direction().name());
            return new ConstructorList(name, null, null, null, null, aggregate.separator(), distinct, aggregate.direction(),
                    stages, entity, settings.toString(), source, keyColumn, valueColumn);
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("Не удалось сохранить источник списка", invalid); }
    }

    public ConstructorList generate(String section, String valuePath, String separator, boolean distinct,
                                    VisualQueryOrder.Direction direction, Set<String> occupied) {
        String name = "list1";
        for (int n = 1; occupied.contains(name); n++) name = "list" + (n + 1);
        return generateNamed(name, section, valuePath, separator, distinct, direction);
    }

    public ConstructorList generateNamed(String name, String section, String valuePath, String separator,
                                         boolean distinct, VisualQueryOrder.Direction direction) {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*") || "main".equals(name))
            throw new IllegalArgumentException("Некорректное имя списка");
        var row = catalog.root(section);
        var metadata = row.javaType().getAnnotation(TableSectionMetadata.class);
        if (metadata == null) throw new IllegalArgumentException("Источник списка должен быть табличной частью");
        var parent = byType(metadata.parentEntity());
        if (valuePath == null || !valuePath.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*"))
            throw new IllegalArgumentException("Некорректный путь значения списка");
        var joins = new ArrayList<VisualQueryDefinition.Join>();
        joins.add(identity(parent.entityName(), "p", "listRow." + metadata.parentField() + ".id"));
        String alias = "listRow";
        var target = row;
        String[] path = valuePath.split("\\.");
        for (int i = 0; i < path.length - 1; i++) {
            String segment = path[i];
            var relation = target.associations().stream().filter(a -> a.name().equals(segment)
                    && a.joinType() == QueryBuilderMetadataCatalog.JoinType.TO_ONE).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Связь списка не разрешена: " + segment));
            target = byType(relation.targetType());
            String next = "listValue" + (i + 1);
            joins.add(identity(target.entityName(), next, alias + "." + segment + ".id"));
            alias = next;
        }
        String fieldName = path[path.length - 1];
        var field = target.fields().stream().filter(f -> f.name().equals(fieldName) && f.javaType() == String.class)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Значение списка должно быть текстовым полем"));
        var aggregate = new VisualQueryDefinition.Aggregate("LISTAGG", alias + "." + fieldName, "value",
                separator, distinct, null, direction);
        var definition = new VisualQueryDefinition(VisualQueryDefinition.CURRENT_VERSION, section, "listRow",
                List.of(new VisualQueryDefinition.SelectField("p.id", "key")), joins, List.of("p.id"), List.of(aggregate), null);
        // Validate every generated path against the same catalog used by execution.
        VisualQueryCompiler.compile(definition, catalog);
        var entity = new QueryBuilderMetadataCatalog.Entity(name, Object.class, List.of(
                new QueryBuilderMetadataCatalog.Field("key", "Ключ документа", Long.class, true, List.of(), parent.javaType()),
                new QueryBuilderMetadataCatalog.Field("value", field.caption(), String.class, false)), List.of(), "Список: " + row.caption());
        var settings = new ObjectMapper().createObjectNode();
        settings.put("version", 1); settings.put("name", name); settings.put("sectionEntity", section);
        settings.put("parentEntity", parent.entityName()); settings.put("parentField", metadata.parentField());
        settings.put("valuePath", valuePath); settings.put("separator", aggregate.separator());
        settings.put("distinct", distinct); settings.put("direction", aggregate.direction().name());
        return new ConstructorList(name, section, parent.entityName(), metadata.parentField(), valuePath,
                aggregate.separator(), distinct, aggregate.direction(), List.of(new VisualQueryPackage.Cte(name, definition)), entity, settings.toString());
    }

    private QueryBuilderMetadataCatalog.Entity byType(Class<?> type) {
        return catalog.roots().stream().filter(e -> e.javaType() == type).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Сущность списка не разрешена: " + type.getName()));
    }

    private static VisualQueryDefinition.Join identity(String entity, String alias, String foreignKey) {
        return new VisualQueryDefinition.Join(null, entity, alias, VisualQueryDefinition.JoinKind.INNER,
                new JoinCondition.Predicate(foreignKey, JoinCondition.Operator.EQ, alias + ".id"));
    }
}
