package org.ipro.reportstudio.query;

import org.ipro.reportstudio.data.QueryField;
import java.util.*;

/** Lowers standalone presentation expressions to identities, not SQL string functions. */
final class QueryPresentationCompiler {
    record Prepared(VisualQueryPackage pack, List<QueryPresentationPlan.Column> columns, List<QueryField> publicFields) { }
    static boolean isPresentation(VisualQueryExpression expression) {
        return expression instanceof VisualQueryExpression.FunctionCall function && function.name().equalsIgnoreCase("Представление");
    }
    static Prepared prepare(VisualQueryPackage pack, QueryBuilderMetadataCatalog catalog) {
        var main = pack.main(); var columns = new ArrayList<QueryPresentationPlan.Column>();
        var selections = new ArrayList<>(main.selectFields()); var expressions = new ArrayList<VisualQueryDefinition.Expression>();
        var fields = new ArrayList<QueryField>();
        var occupied = new HashSet<String>(); main.selectFields().forEach(field -> occupied.add(field.resultName()));
        main.expressions().forEach(field -> occupied.add(field.resultName()));
        for (var expression : main.expressions()) {
            if (!isPresentation(expression.expression())) { expressions.add(expression); continue; }
            if (!main.groupBy().isEmpty() || !main.aggregates().isEmpty() || main.having() != null)
                throw new IllegalArgumentException("Представление поддерживается только в SELECT без группировки и агрегатов");
            var function = (VisualQueryExpression.FunctionCall) expression.expression();
            if (function.arguments().size() != 1 || !(function.arguments().getFirst() instanceof VisualQueryExpression.FieldRef reference))
                throw new IllegalArgumentException("Представление требует сущность или ссылочное поле");
            String path = reference.path(); int dot = path.indexOf('.');
            String alias = dot < 0 ? path : path.substring(0, dot); String relative = dot < 0 ? "" : path.substring(dot + 1);
            String source = source(main, alias, catalog);
            String id = unique("presentation_id_", occupied); String typeColumn = null; Class<?> type;
            if (relative.equals("recorder")) {
                boolean virtual = pack.ctes().stream().anyMatch(cte -> cte.name().equals(source));
                if (virtual) {
                    var cte = pack.ctes().stream().filter(candidate -> candidate.name().equals(source)).findFirst().orElseThrow();
                    if (cte.definition().selectFields().stream().noneMatch(field -> field.resultName().equals("recorder_id"))
                            || cte.definition().selectFields().stream().noneMatch(field -> field.resultName().equals("recorder_typeCode")))
                        throw new IllegalArgumentException("Источник не содержит ссылку регистратора: " + path);
                } else if (!org.ipro.register.RecordedRegisterEntry.class.isAssignableFrom(catalog.root(source).javaType()))
                    throw new IllegalArgumentException("Источник не содержит регистратора: " + path);
                typeColumn = unique("presentation_type_", occupied);
                selections.add(new VisualQueryDefinition.SelectField(alias + (virtual ? ".recorder_id" : ".recorder.id"), id));
                selections.add(new VisualQueryDefinition.SelectField(alias + (virtual ? ".recorder_typeCode" : ".recorder.typeCode"), typeColumn));
                type = null;
            } else {
                type = target(source, relative, pack, catalog, new HashSet<>());
                boolean virtual = pack.ctes().stream().anyMatch(cte -> cte.name().equals(source));
                if (!relative.isEmpty() && !virtual) expressions.add(new VisualQueryDefinition.Expression(id,
                    new VisualQueryExpression.FunctionCall("COALESCE", List.of(
                        new VisualQueryExpression.FunctionCall("FK", List.of(new VisualQueryExpression.FieldRef(path))),
                        new VisualQueryExpression.Literal(null)))));
                else selections.add(new VisualQueryDefinition.SelectField(relative.isEmpty() ? alias + ".id" : path, id));
            }
            if (main.orders().stream().anyMatch(order -> order.path().equals(expression.resultName()) || order.path().equals(path)))
                throw new IllegalArgumentException("Сортировка по представлению пока не поддерживается");
            columns.add(new QueryPresentationPlan.Column(expression.resultName(), id, typeColumn, type));
            fields.add(new QueryField(expression.resultName(), "", String.class, expression.resultName(), false, false, false));
        }
        var lowered = new VisualQueryDefinition(main.version(), main.entityName(), main.entityAlias(), selections, main.joins(),
            main.groupBy(), main.aggregates(), expressions, main.having(), main.where(), main.parameters(), main.orders(), main.subqueries());
        return new Prepared(new VisualQueryPackage(pack.version(), pack.ctes(), lowered), columns, fields);
    }
    private static String unique(String prefix, Set<String> occupied) {
        int index = 1; while (!occupied.add(prefix + index)) index++; return prefix + index;
    }
    private static String source(VisualQueryDefinition definition, String alias, QueryBuilderMetadataCatalog catalog) {
        if (definition.entityAlias().equals(alias)) return definition.entityName();
        var join = definition.joins().stream().filter(value -> value.alias().equals(alias)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Неизвестный alias: " + alias));
        if (join.independent()) return join.sourcePath();
        String parent = join.parentAlias() == null ? definition.entityAlias() : join.parentAlias();
        var parentEntity = catalog.root(source(definition, parent, catalog));
        var association = parentEntity.associations().stream().filter(value -> value.name().equals(join.sourcePath())).findFirst().orElseThrow();
        return catalog.roots().stream().filter(value -> value.javaType() == association.targetType()).findFirst().orElseThrow().entityName();
    }
    private static Class<?> target(String source, String field, VisualQueryPackage pack, QueryBuilderMetadataCatalog catalog, Set<String> visiting) {
        if (!visiting.add(source + "." + field)) throw new IllegalArgumentException("Циклическая ссылка представления");
        var stage = pack.ctes().stream().filter(cte -> cte.name().equals(source)).findFirst();
        if (stage.isPresent()) {
            var definition = stage.get().definition();
            var selected = definition.selectFields().stream().filter(value -> value.resultName().equals(field)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Представление требует типизированную ссылку: " + field));
            String path = selected.path(); int dot = path.indexOf('.');
            String next = source(definition, path.substring(0, dot), catalog); String relative = path.substring(dot + 1);
            if (relative.equals("id")) relative = "";
            else if (relative.endsWith(".id")) relative = relative.substring(0, relative.length() - 3);
            return target(next, relative, pack, catalog, visiting);
        }
        var entity = catalog.root(source);
        if (field.isEmpty()) return entity.javaType();
        String[] parts = field.split("\\.");
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index];
            var association = entity.associations().stream().filter(value -> value.name().equals(part)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Представление требует ссылку, а не скаляр: " + field));
            if (association.joinType() == QueryBuilderMetadataCatalog.JoinType.COLLECTION) throw new IllegalArgumentException("Представление требует одиночную ссылку");
            if (index == parts.length - 1) return association.targetType();
            entity = catalog.roots().stream().filter(value -> value.javaType() == association.targetType()).findFirst().orElseThrow();
        }
        return entity.javaType();
    }
}
