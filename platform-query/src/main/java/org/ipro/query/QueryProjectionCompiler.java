package org.ipro.query;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.query.sqm.tree.domain.SqmPath;
import org.hibernate.query.sqm.tree.from.SqmAttributeJoin;
import org.hibernate.query.sqm.tree.from.SqmFrom;
import org.hibernate.query.sqm.tree.from.SqmRoot;
import org.hibernate.query.sqm.tree.select.SqmSelectStatement;
import org.ipro.filtergrid.projection.ColumnMeta;
import org.ipro.filtergrid.projection.DataType;
import org.ipro.filtergrid.projection.HqlQueryParts;
import org.ipro.filtergrid.projection.NullKeyPolicy;
import org.ipro.filtergrid.projection.ProjectionDefinition;
import org.ipro.filtergrid.projection.ProjectionExecutionMode;
import org.ipro.filtergrid.projection.QueryProvenance;
import org.ipro.query.model.QueryDefinition;
import org.ipro.query.model.QueryLimits;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Prepares a checked scalar projection and the bindings for one report run. */
public final class QueryProjectionCompiler {
    private static final Pattern ALIAS = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final EntityManagerFactory factory;
    private final ReportQueryAssemblyService assembly;
    private final ReportQueryGuard guard;

    public QueryProjectionCompiler(EntityManagerFactory factory, ReportQueryAssemblyService assembly,
                                   ReportQueryGuard guard) {
        this.factory = Objects.requireNonNull(factory);
        this.assembly = Objects.requireNonNull(assembly);
        this.guard = Objects.requireNonNull(guard);
    }

    public PreparedProjection compile(String name, QueryDefinition query, ReportContext context,
                                      Map<String, Object> formValues, ProjectionExecutionMode mode,
                                      int hybridMaxRows, String commandColumn) {
        var prepared = assembly.assembleProjection(query, context, formValues, this::lowerEntitySelection);
        String hql = ServiceParams.expand(prepared.jpql());
        var statement = select(hql);
        var checked = guard.guard(hql, prepared.bindings().keySet());
        requireAllowed(checked);
        var shape = projectionShape(hql, statement, prepared.presentations());
        requireAllowed(guard.guard(shape.wrapped(), prepared.bindings().keySet()));

        var builder = ProjectionDefinition.define(name).queryParts(HqlQueryParts.builder()
                        .selectFrom(shape.wrapped()).orderBy(shape.orderBy()).build())
                .queryProvenance(QueryProvenance.REGISTERED).mode(mode);
        if (mode == ProjectionExecutionMode.HYBRID) {
            if (hybridMaxRows < 1 || hybridMaxRows > query.limits().maxRows()) {
                throw new IllegalArgumentException("HYBRID требует положительный предел в рамках лимита запроса");
            }
            builder.maxRows(hybridMaxRows);
        }
        if (shape.key().isEmpty()) builder.withoutRowKey();
        else builder.rowKey(shape.key().toArray(String[]::new));
        if (shape.grouped()) builder.rowKeyNullPolicy(NullKeyPolicy.ALLOW);

        for (var column : columns(checked, shape)) builder.column(column);
        var bindings = new LinkedHashMap<String, Object>();
        for (var parameter : statement.getSqmParameters()) {
            String parameterName = parameter.getName();
            if (parameterName == null || !prepared.bindings().containsKey(parameterName)) {
                throw new IllegalArgumentException("Не заполнен параметр проекции: " + parameterName);
            }
            Object value = prepared.bindings().get(parameterName);
            Class<?> javaType = parameter.getParameterType();
            builder.parameter(parameterName, parameterType(javaType, value), value != null);
            bindings.put(parameterName, value);
        }
        Class<?> commandType = commandColumn == null ? null : shape.identities().get(commandColumn);
        if (commandColumn != null && commandType == null) {
            throw new IllegalArgumentException("Колонка объекта команды должна содержать идентификатор сущности: "
                    + commandColumn);
        }
        return new PreparedProjection(builder.build(), bindings, prepared.presentations(), query.limits(),
                commandType == null ? null : new CommandObject(commandColumn, commandType));
    }

    /** Проверяет структуру проекции без параметров запуска и чтения данных. */
    public List<ColumnMeta> describe(QueryDefinition query) {
        return inspect(query).columns();
    }

    /**
     * Та же проверка структуры, но с тем, что видно администратору: сущности, которые читает запрос
     * (для «Кому виден»), и алиасы колонок, составляющие ключ строки.
     */
    public Inspection inspect(QueryDefinition query) {
        var prepared = assembly.describeProjection(query, this::lowerEntitySelection);
        String hql = prepared.jpql();
        var statement = select(hql);
        if (statement.getSqmParameters().stream().anyMatch(parameter -> parameter.getName() == null)) {
            throw new IllegalArgumentException("Проекция поддерживает только именованные параметры");
        }
        var checked = guard.guardStructure(hql, prepared.bindings().keySet(), Map.of());
        requireAllowed(checked);
        var shape = projectionShape(hql, statement, prepared.presentations());
        requireAllowed(guard.guardStructure(shape.wrapped(), prepared.bindings().keySet(), Map.of()));
        var entities = checked.analysis() == null ? List.<String>of()
                : checked.analysis().entities().stream().map(EntityUsage::entityName).distinct().toList();
        return new Inspection(columns(checked, shape), entities, shape.key());
    }

    /**
     * @param columns  колонки проекции в порядке запроса
     * @param entities имена сущностей, к которым обращается запрос (корни, соединения, подзапросы)
     * @param rowKey   алиасы колонок ключа строки; пусто, если ключ строки определить нельзя
     */
    public record Inspection(List<ColumnMeta> columns, List<String> entities, List<String> rowKey) {
        public Inspection {
            columns = List.copyOf(columns);
            entities = List.copyOf(entities);
            rowKey = List.copyOf(rowKey);
        }
    }

    /** Проверяет допуск текущего пользователя до ввода значений параметров. */
    public void requireReadAccess(QueryDefinition query) {
        requireReadAccess(query, null);
    }

    /** Applies query admission to the subject of an administrative preview. */
    public void requireReadAccess(QueryDefinition query, String previewUser) {
        var prepared = assembly.describeProjection(query, this::lowerEntitySelection);
        String hql = prepared.jpql();
        var parameterTypes = assembly.visualParameterEntityClasses(query);
        requireAllowed(guard.guardForUser(hql, prepared.bindings().keySet(), parameterTypes, previewUser));
        var shape = projectionShape(hql, select(hql), prepared.presentations());
        requireAllowed(guard.guardForUser(shape.wrapped(), prepared.bindings().keySet(), parameterTypes, previewUser));
    }

    public boolean isCommandEntity(String className) {
        return factory.getMetamodel().getEntities().stream()
                .map(entity -> entity.getJavaType())
                .anyMatch(type -> type.getName().equals(className)
                        && org.ipro.identity.IdentifiableEntity.class.isAssignableFrom(type));
    }

    private ProjectionShape projectionShape(String hql, SqmSelectStatement<?> statement,
                                            QueryPresentationPlan presentations) {
        var spec = statement.getQuerySpec();
        var selections = spec.getSelectClause().getSelections();
        var identities = new LinkedHashMap<String, Class<?>>();
        var aliasByPath = new LinkedHashMap<String, String>();
        var aliases = new ArrayList<String>();
        for (var selection : selections) {
            String alias = selection.getAlias();
            if (alias == null || !ALIAS.matcher(alias).matches() || aliases.stream().anyMatch(alias::equalsIgnoreCase)) {
                throw new IllegalArgumentException("Для каждой колонки проекции нужен уникальный алиас SELECT");
            }
            aliases.add(alias);
            if (selection.getSelectableNode() instanceof SqmPath<?> path) {
                aliasByPath.putIfAbsent(path.getNavigablePath().getFullPath(), alias);
                var type = identityType(path);
                if (type != null) identities.put(alias, type);
            }
        }
        for (var presentation : presentations.columns()) {
            if (presentation.entityType() != null) {
                identities.putIfAbsent(presentation.idColumn(), presentation.entityType());
            }
        }

        var key = new ArrayList<String>();
        boolean grouped = !spec.getGroupByClauseExpressions().isEmpty();
        if (grouped) {
            for (var expression : spec.getGroupByClauseExpressions()) {
                String alias = selectedAlias(expression, aliases, aliasByPath);
                if (alias == null) {
                    key.clear();
                    break;
                }
                if (!key.contains(alias)) key.add(alias);
            }
        } else if (spec.getFromClause().getRoots().size() == 1) {
            var root = spec.getFromClause().getRoots().getFirst();
            if (toOneJoins(root)) {
                for (var selection : selections) {
                    if (selection.getSelectableNode() instanceof SqmPath<?> path
                            && path.getLhs() == root && identities.containsKey(selection.getAlias())) {
                        key.add(selection.getAlias());
                        break;
                    }
                }
            }
        }

        if (spec.getOffsetExpression() != null || spec.getFetchExpression() != null) {
            throw new IllegalArgumentException("Проекция не поддерживает LIMIT, OFFSET или FETCH в исходном запросе");
        }
        var orderBy = new ArrayList<String>();
        for (var sort : spec.getSortSpecifications()) {
            String alias = selectedAlias(sort.getSortExpression(), aliases, aliasByPath);
            if (alias == null || sort.isIgnoreCase()) {
                throw new IllegalArgumentException("Исходная сортировка проекции должна ссылаться на колонку SELECT");
            }
            String nulls = switch (sort.getNullPrecedence()) {
                case FIRST -> " nulls first";
                case LAST -> " nulls last";
                case null, default -> "";
            };
            orderBy.add(alias + (sort.isAscending() ? " asc" : " desc") + nulls);
        }
        int order = HqlQueryParts.topLevelClause(hql, "ORDER BY");
        String source = order < 0 ? hql : hql.substring(0, order).stripTrailing();
        String columns = aliases.stream().map(alias -> "t." + alias + " as " + alias)
                .collect(java.util.stream.Collectors.joining(", "));
        String wrapped = "select " + columns + " from (" + source + ") t";
        return new ProjectionShape(wrapped, String.join(", ", orderBy), key, grouped, identities);
    }

    private static List<ColumnMeta> columns(GuardResult checked, ProjectionShape shape) {
        return checked.selectFields().stream().map(field -> {
            DataType type = dataType(field.javaType());
            Class<?> reference = shape.identities().get(field.name());
            return new ColumnMeta(field.name(), field.caption(), type, true, true, true,
                    reference, reference == null && field.aggregatable());
        }).toList();
    }

    private record ProjectionShape(String wrapped, String orderBy, List<String> key, boolean grouped,
                                   Map<String, Class<?>> identities) { }

    private static String selectedAlias(Object expression, List<String> aliases, Map<String, String> aliasByPath) {
        if (expression instanceof org.hibernate.query.sqm.tree.expression.SqmAliasedNodeRef reference) {
            return aliases.get(reference.getPosition() - 1);
        }
        return expression instanceof SqmPath<?> path ? aliasByPath.get(path.getNavigablePath().getFullPath()) : null;
    }

    private String lowerEntitySelection(String hql) {
        var select = select(ServiceParams.expand(hql));
        var selections = select.getQuerySpec().getSelectClause().getSelections();
        if (selections.size() != 1 || !(selections.getFirst().getSelectableNode() instanceof SqmRoot<?> root)
                || !(root.getNodeType() instanceof org.hibernate.query.sqm.tree.domain.SqmEntityDomainType<?> type)) {
            return hql;
        }
        String alias = root.getExplicitAlias();
        var entity = factory.getMetamodel().entity(type.getJavaType());
        int from = HqlQueryParts.topLevelClause(hql, "FROM");
        if (alias == null || !ALIAS.matcher(alias).matches() || from < 0
                || !entity.hasSingleIdAttribute() || entity.getIdType() == null
                || entity.getIdType().getJavaType() != Long.class
                || !hql.stripLeading().matches("(?is)^select\\s+(?:distinct\\s+)?" + Pattern.quote(alias)
                    + "(?:\\s+(?:as\\s+)?[a-z_][a-z0-9_]*)?\\s+from\\s+.*")) {
            throw new IllegalArgumentException("Для преобразования сущности нужен SELECT корневого алиаса с ключом Long");
        }
        String label = selections.getFirst().getAlias();
        if (label == null) label = "entityLabel";
        String distinct = select.getQuerySpec().getSelectClause().isDistinct() ? "distinct " : "";
        return "select " + distinct + "Представление(" + alias + ") as " + label + hql.substring(from);
    }

    private SqmSelectStatement<?> select(String hql) {
        var engine = factory.unwrap(SessionFactoryImplementor.class).getQueryEngine();
        var sqm = engine.getInterpretationCache().resolveHqlInterpretation(hql, null, engine.getHqlTranslator())
                .getSqmStatement();
        if (!(sqm instanceof SqmSelectStatement<?> select)
                || !(select.getQueryPart() instanceof org.hibernate.query.sqm.tree.select.SqmQuerySpec<?>)
                || select.getCteStatements().stream().anyMatch(cte -> cte.getCteDefinition().getQueryPart() instanceof org.hibernate.query.sqm.tree.select.SqmQueryGroup<?>)) {
            throw new IllegalArgumentException("Проекция требует SELECT без операторов множеств и рекурсивных CTE");
        }
        return select;
    }

    private static boolean toOneJoins(SqmFrom<?, ?> from) {
        return from.getSqmJoins().stream().allMatch(join -> join instanceof SqmAttributeJoin<?, ?> association
                && !association.getAttribute().isCollection()
                && (join.getSqmJoinType() == org.hibernate.query.sqm.tree.SqmJoinType.INNER
                    || join.getSqmJoinType() == org.hibernate.query.sqm.tree.SqmJoinType.LEFT)
                && toOneJoins(join));
    }

    private Class<?> identityType(SqmPath<?> path) {
        if (!(path.getLhs() instanceof SqmFrom<?, ?> owner)
                || !(owner.getNodeType() instanceof org.hibernate.query.sqm.tree.domain.SqmEntityDomainType<?> type)) {
            return null;
        }
        var entity = factory.getMetamodel().entity(type.getJavaType());
        if (!entity.hasSingleIdAttribute() || entity.getIdType() == null) return null;
        return entity.getId(entity.getIdType().getJavaType()).getName()
                .equals(path.getReferencedPathSource().getPathName()) ? type.getJavaType() : null;
    }

    private static DataType parameterType(Class<?> type, Object value) {
        if (value instanceof java.util.Collection<?> || type == null || type == Object.class) {
            return DataType.REF;
        }
        try {
            return dataType(type);
        } catch (IllegalArgumentException unsupported) {
            return DataType.REF;
        }
    }

    private static DataType dataType(Class<?> type) {
        if (type == String.class) return DataType.STRING;
        if (type == Long.class || type == long.class) return DataType.LONG;
        if (org.ipro.report.data.QueryField.isNumber(type)) return DataType.NUMBER;
        if (type == Boolean.class || type == boolean.class) return DataType.BOOLEAN;
        if (type == LocalDate.class) return DataType.DATE;
        if (type == LocalDateTime.class) return DataType.DATETIME;
        throw new IllegalArgumentException("Тип колонки не поддерживается в проекции: " + type.getTypeName());
    }

    private static void requireAllowed(GuardResult result) {
        if (!result.allowed()) {
            throw new IllegalArgumentException("Отказ: " + String.join("; ", result.errors()));
        }
    }

    public record CommandObject(String column, Class<?> entityType) { }

    public record PreparedProjection(ProjectionDefinition definition, Map<String, Object> bindings,
                                     QueryPresentationPlan presentations, QueryLimits limits,
                                     CommandObject commandObject) {
        public PreparedProjection {
            bindings = Collections.unmodifiableMap(new LinkedHashMap<>(bindings));
        }
    }
}

