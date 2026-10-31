package org.ipro.query.register;

import org.ipro.filtergrid.filter.FilterCondition;
import org.ipro.filtergrid.filter.FilterConditionNode;
import org.ipro.filtergrid.filter.FilterDataType;
import org.ipro.filtergrid.filter.FilterGroup;
import org.ipro.filtergrid.filter.FilterNode;
import org.ipro.filtergrid.filter.FilterOperator;
import org.ipro.filtergrid.filter.LogicalOperator;
import org.ipro.register.SliceSpec;
import org.ipro.register.SliceSpecValidator;
import org.ipro.query.JoinCondition;
import org.ipro.query.JoinLogicalOperator;
import org.ipro.query.QueryBuilderMetadataCatalog;
import org.ipro.query.ReportQueryAssembler;
import org.ipro.query.VisualQueryCompiler;
import org.ipro.query.VisualQueryDefinition;
import org.ipro.query.VisualQueryPackage;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure generation of ordinary visual CTE AST. Compilation and final authorization remain separate. */
public final class RegisterSliceCteGenerator {
    public enum Role { DIMENSION, PERIOD, RESOURCE, RECORDER }
    public record Column(String field, String name, Class<?> javaType, Role role) { }

    /** Immutable caller-owned namespace; reuse the returned context when generating another fragment. */
    public record NameContext(Set<String> occupied) {
        public NameContext { occupied = Set.copyOf(Objects.requireNonNull(occupied, "occupied")); }
        public static NameContext empty() { return new NameContext(Set.of()); }
        public static NameContext from(VisualQueryPackage pack) {
            Set<String> names = new LinkedHashSet<>();
            pack.ctes().forEach(cte -> { names.add(cte.name()); collect(cte.definition(), names); });
            collect(pack.main(), names);
            return new NameContext(names);
        }
        private static void collect(VisualQueryDefinition definition, Set<String> names) {
            names.add(definition.entityName()); names.add(definition.entityAlias());
            definition.joins().forEach(join -> { names.add(join.alias()); names.add(join.sourcePath()); });
            definition.selectFields().forEach(field -> names.add(field.resultName()));
            definition.aggregates().forEach(field -> names.add(field.resultName()));
            definition.expressions().forEach(field -> names.add(field.resultName()));
            definition.parameters().forEach(parameter -> { reservedBinding(parameter.name()); names.add(parameter.name()); });
            if (definition.having() != null) definition.having().conditions().forEach(condition -> {
                if (condition.value() instanceof VisualQueryDefinition.HavingParamRef parameter) {
                    reservedBinding(parameter.name()); names.add(parameter.name());
                }
            });
            definition.subqueries().forEach(query -> { names.add(query.name()); collect(query.definition(), names); });
        }
        private static void reservedBinding(String name) {
            if (name.matches("visualFilter_[0-9]+"))
                throw new IllegalArgumentException("SLICE_BINDING_NAMESPACE: visualFilter_N принадлежит общему compiler context");
        }
    }

    /** Column map exposes scalar identities for references. Final bindings come from one package compilation. */
    public record Fragment(List<VisualQueryPackage.Cte> ctes, List<Column> columns,
                           Map<String, String> columnNames, String latestName, String outputAlias,
                           Set<String> sourceEntities, NameContext nextContext) {
        public Fragment {
            ctes = List.copyOf(ctes); columns = List.copyOf(columns);
            columnNames = Collections.unmodifiableMap(new LinkedHashMap<>(columnNames));
            sourceEntities = Set.copyOf(sourceEntities);
            Objects.requireNonNull(nextContext);
        }
        public VisualQueryPackage asPackage() {
            return new VisualQueryPackage(ctes, withParameters(definition(latestName, outputAlias,
                columns.stream().map(column -> field(outputAlias + "." + column.name(), column.name())).toList(),
                List.of(), List.of(), List.of(), null), parameters()));
        }
        private List<VisualQueryDefinition.Parameter> parameters() {
            return ctes.getFirst().definition().parameters();
        }
        public ReportQueryAssembler compile(QueryBuilderMetadataCatalog catalog) {
            return VisualQueryCompiler.compile(asPackage(), Objects.requireNonNull(catalog));
        }
        /** Existing CTEs remain before this fragment; the consumer's main query is retained verbatim. */
        public VisualQueryPackage appendTo(VisualQueryPackage consumer) {
            NameContext.from(consumer); // Validate the compiler's reserved parameter namespace.
            Set<String> generated = ctes.stream().map(VisualQueryPackage.Cte::name)
                .map(name -> name.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
            for (var cte : consumer.ctes()) {
                if (generated.contains(cte.name().toLowerCase(Locale.ROOT))
                        || sourceEntities.stream().anyMatch(name -> name.equalsIgnoreCase(cte.name())))
                    throw new IllegalArgumentException("SLICE_NAME_COLLISION: " + cte.name());
            }
            var combined = new ArrayList<>(consumer.ctes()); combined.addAll(ctes);
            var parameters = new ArrayList<>(consumer.main().parameters());
            for (var required : parameters()) {
                var existing = parameters.stream().filter(p -> p.name().equals(required.name())).findFirst();
                if (existing.isPresent() && !existing.get().equals(required))
                    throw new IllegalArgumentException("SLICE_PARAMETER_CONFLICT: " + required.name());
                if (existing.isEmpty()) parameters.add(required);
            }
            return new VisualQueryPackage(VisualQueryPackage.CURRENT_VERSION, combined,
                withParameters(consumer.main(), parameters));
        }
    }

    public Fragment generate(RegisterSliceSource source, SliceSpec spec, NameContext context) {
        return generate(source, spec, context, null);
    }

    /** Date is supplied at report execution; spec.asOf is replaced, never used as a runtime default. */
    public Fragment generateParameterized(RegisterSliceSource source, SliceSpec spec, NameContext context,
                                          String dateParameter) {
        var parameter = new VisualQueryDefinition.Parameter(dateParameter, "DATETIME", true, null);
        NameContext.reservedBinding(parameter.name());
        var occupied = new LinkedHashSet<>(context.occupied()); occupied.add(parameter.name());
        return generate(source, spec, new NameContext(occupied), parameter);
    }

    private Fragment generate(RegisterSliceSource source, SliceSpec spec, NameContext context,
                              VisualQueryDefinition.Parameter dateParameter) {
        return generate(source, spec, context, dateParameter, List.of());
    }

    public Fragment generateParameterized(RegisterSliceSource source, SliceSpec spec, NameContext context,
                                          String dateParameter, List<SliceParameterFilter> filters) {
        var parameter = new VisualQueryDefinition.Parameter(dateParameter, "DATETIME", true, null);
        NameContext.reservedBinding(dateParameter);
        if (dateParameter.startsWith("sliceId_")) throw new IllegalArgumentException("Зарезервированное имя параметра: " + dateParameter);
        var occupied = new LinkedHashSet<>(context.occupied()); occupied.add(dateParameter);
        filters.forEach(filter -> { occupied.add(filter.parameter()); occupied.add("sliceId_" + filter.parameter()); });
        return generate(source, spec, new NameContext(occupied), parameter, filters);
    }

    private Fragment generate(RegisterSliceSource source, SliceSpec spec, NameContext context,
                              VisualQueryDefinition.Parameter dateParameter, List<SliceParameterFilter> parameterFilters) {
        Objects.requireNonNull(source); Objects.requireNonNull(spec); Objects.requireNonNull(context);
        var descriptor = source.descriptor();
        Map<String, Class<?>> types = new LinkedHashMap<>();
        source.dimensions().forEach(dimension -> types.put(dimension.field(), dimension.comparisonType()));
        Map<String, Class<?>> resourceTypes = new LinkedHashMap<>();
        source.resources().forEach(resource -> resourceTypes.put(resource.field(), resource.comparisonType()));
        SliceSpecValidator.validate(descriptor, spec, types, resourceTypes);
        var declarations = new ArrayList<VisualQueryDefinition.Parameter>();
        if (dateParameter != null) declarations.add(dateParameter);
        for (var filter : parameterFilters) {
            NameContext.reservedBinding(filter.parameter());
            var permitted = filter.afterSlice() ? resourceTypes : types;
            Class<?> type = permitted.get(filter.field());
            if (filter.afterSlice() && descriptor.period().orElseThrow().name().equals(filter.field())) type = LocalDateTime.class;
            if (type == null) throw new IllegalArgumentException("Поле недоступно в этой фазе отбора: " + filter.field());
            if (!(Number.class.isAssignableFrom(SliceSpecValidator.boxed(type)) || type == LocalDate.class || type == LocalDateTime.class)
                    && filter.operator() != SliceSpec.Operator.EQ && filter.operator() != SliceSpec.Operator.NE)
                throw new IllegalArgumentException("Для поля доступны только = и <>: " + filter.field());
            String dataType = type == LocalDateTime.class ? "DATETIME" : type == LocalDate.class ? "DATE" : type.getName();
            boolean reference = java.util.stream.Stream.concat(source.dimensions().stream(), source.resources().stream())
                .anyMatch(field -> field.field().equals(filter.field()) && field.reference());
            if (reference && filter.operator() != SliceSpec.Operator.EQ && filter.operator() != SliceSpec.Operator.NE)
                throw new IllegalArgumentException("Для ссылки доступны только = и <>: " + filter.field());
            String bindingName = reference ? "sliceId_" + filter.parameter() : filter.parameter();
            var declaration = new VisualQueryDefinition.Parameter(bindingName, dataType, true, null);
            var existing = declarations.stream().filter(p -> p.name().equals(bindingName)).findFirst();
            if (existing.isPresent() && !existing.get().dataType().equals(dataType)) throw new IllegalArgumentException("Несовместимые типы параметра: " + filter.parameter());
            if (existing.isEmpty()) declarations.add(declaration);
        }
        var period = descriptor.period().orElseThrow();
        if (descriptor.recorded()) {
            var expectedWinnerOrder = List.of(period.name(), descriptor.recordingOrder().orElseThrow().name(),
                descriptor.lineNumber().orElseThrow().name());
            if (!descriptor.winnerOrderFields().equals(expectedWinnerOrder))
                throw new IllegalArgumentException("SLICE_WINNER_ORDER: подчинённый CTE поддерживает порядок "
                    + expectedWinnerOrder + ", descriptor объявил " + descriptor.winnerOrderFields());
        }

        Set<String> occupied = new LinkedHashSet<>(context.occupied()); occupied.addAll(source.entityNames());
        Set<String> folded = occupied.stream().map(name -> name.toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toSet());
        String prefix;
        Set<String> allocated;
        int candidate = 1;
        do {
            prefix = "rs" + candidate++ + "_";
            allocated = names(prefix, source);
        } while (allocated.stream().anyMatch(name -> folded.contains(name.toLowerCase(Locale.ROOT))));
        occupied.addAll(allocated);
        String row = prefix + "row", group = prefix + "group", mx = prefix + "mx", latest = prefix + "latest";
        String orderMax = prefix + "orderMax", lineMax = prefix + "lineMax";
        String maximum = prefix + "max_period", maximumOrder = prefix + "max_order";
        String maximumLine = prefix + "max_line", periodColumn = prefix + "period";
        String recordingOrderColumn = prefix + "recording_order";
        var joins = new ArrayList<VisualQueryDefinition.Join>();
        var keys = new ArrayList<VisualQueryDefinition.SelectField>();
        var groups = new ArrayList<String>();
        var on = new ArrayList<JoinCondition>();
        var columns = new ArrayList<Column>();
        Map<String, String> columnNames = new LinkedHashMap<>();
        Map<String, String> filterPaths = new LinkedHashMap<>();
        for (int index = 0; index < source.dimensions().size(); index++) {
            var dimension = source.dimensions().get(index);
            String path = row + "." + dimension.field();
            if (dimension.reference()) {
                String target = prefix + "target" + index;
                joins.add(new VisualQueryDefinition.Join(null, dimension.targetEntity(), target,
                    VisualQueryDefinition.JoinKind.INNER, eq(path + "." + dimension.targetId(), target + "." + dimension.targetId())));
                path = target + "." + dimension.targetId();
            }
            String column = prefix + "d" + index;
            keys.add(field(path, column)); groups.add(path); on.add(eq(path, group + "." + column));
            columns.add(new Column(dimension.field(), column, dimension.comparisonType(), Role.DIMENSION));
            columnNames.put(dimension.field(), column); filterPaths.put(dimension.field(), path);
        }
        String periodPath = row + "." + period.name();
        on.add(eq(periodPath, group + "." + maximum));
        var before = new ArrayList<FilterNode>();
        before.add(dateParameter == null ? condition(periodPath, SliceSpec.Operator.LE, spec.asOf(), LocalDateTime.class)
            : new FilterConditionNode(new FilterCondition(periodPath, FilterOperator.LE,
                ":" + dateParameter.name(), null, FilterDataType.DATE)));
        spec.dimensions().forEach(value -> before.add(condition(filterPaths.get(value.field()), value.operator(),
            value.value(), types.get(value.field()))));
        parameterFilters.stream().filter(value -> !value.afterSlice()).forEach(value -> before.add(parameterCondition(
            filterPaths.get(value.field()), value, types.get(value.field()), source.dimensions().stream().anyMatch(field -> field.field().equals(value.field()) && field.reference()))));
        var maximumDefinition = withParameters(definition(descriptor.entityName(), row, keys, joins, groups,
            List.of(new VisualQueryDefinition.Aggregate("MAX", periodPath, maximum)), and(before)),
            declarations);

        var latestFields = new ArrayList<>(keys);
        latestFields.add(field(periodPath, periodColumn)); columnNames.put(period.name(), periodColumn);
        columns.add(new Column(period.name(), periodColumn, period.javaType(), Role.PERIOD));
        var resourceJoins = new ArrayList<VisualQueryDefinition.Join>();
        for (int index = 0; index < source.resources().size(); index++) {
            var resource = source.resources().get(index);
            String column = prefix + "r" + index;
            String path = row + "." + resource.field();
            if (resource.reference()) {
                String target = prefix + "resourceTarget" + index;
                resourceJoins.add(new VisualQueryDefinition.Join(null, resource.targetEntity(), target,
                    VisualQueryDefinition.JoinKind.INNER, eq(path + "." + resource.targetId(), target + "." + resource.targetId())));
                path = target + "." + resource.targetId();
            }
            latestFields.add(field(path, column)); columnNames.put(resource.field(), column);
            filterPaths.put(resource.field(), path);
            columns.add(new Column(resource.field(), column, resource.comparisonType(), Role.RESOURCE));
        }
        var generatedCtes = new ArrayList<VisualQueryPackage.Cte>();
        var selectedRecorders = spec.recorderReadIntent().selectedRecorders();
        generatedCtes.add(new VisualQueryPackage.Cte(mx, maximumDefinition, selectedRecorders));
        var latestJoins = new ArrayList<>(joins);
        latestJoins.addAll(resourceJoins);
        latestJoins.add(new VisualQueryDefinition.Join(null, mx, group, VisualQueryDefinition.JoinKind.INNER,
            new JoinCondition.Group(JoinLogicalOperator.AND, on)));

        if (descriptor.recorded()) {
            String orderField = descriptor.winnerOrderFields().get(1);
            String lineField = descriptor.winnerOrderFields().get(2);
            String orderPath = row + "." + orderField;
            String linePath = row + "." + lineField;
            var periodGroups = new ArrayList<>(groups);
            periodGroups.add(periodPath);
            var periodFields = new ArrayList<>(keys);
            periodFields.add(field(periodPath, periodColumn));
            var orderJoins = new ArrayList<>(joins);
            orderJoins.add(new VisualQueryDefinition.Join(null, mx, group, VisualQueryDefinition.JoinKind.INNER,
                new JoinCondition.Group(JoinLogicalOperator.AND, on)));
            var orderDefinition = definition(descriptor.entityName(), row, periodFields, orderJoins, periodGroups,
                List.of(new VisualQueryDefinition.Aggregate("MAX", orderPath, maximumOrder)), null);
            generatedCtes.add(new VisualQueryPackage.Cte(orderMax, orderDefinition, selectedRecorders));

            var orderGroupFields = new ArrayList<>(periodFields);
            orderGroupFields.add(field(orderPath, recordingOrderColumn));
            var lineGroups = new ArrayList<>(periodGroups);
            lineGroups.add(orderPath);
            var lineOnOrder = new ArrayList<>(on);
            lineOnOrder.add(eq(periodPath, orderMax + "." + periodColumn));
            lineOnOrder.add(eq(orderPath, orderMax + "." + maximumOrder));
            var lineJoins = new ArrayList<>(joins);
            lineJoins.add(new VisualQueryDefinition.Join(null, mx, group, VisualQueryDefinition.JoinKind.INNER,
                new JoinCondition.Group(JoinLogicalOperator.AND, on)));
            lineJoins.add(new VisualQueryDefinition.Join(null, orderMax, orderMax,
                VisualQueryDefinition.JoinKind.INNER, new JoinCondition.Group(JoinLogicalOperator.AND, lineOnOrder)));
            var lineDefinition = definition(descriptor.entityName(), row, orderGroupFields, lineJoins, lineGroups,
                List.of(new VisualQueryDefinition.Aggregate("MAX", linePath, maximumLine)), null);
            generatedCtes.add(new VisualQueryPackage.Cte(lineMax, lineDefinition, selectedRecorders));

            var orderLatestOn = new ArrayList<JoinCondition>();
            for (int index = 0; index < groups.size(); index++)
                orderLatestOn.add(eq(groups.get(index), orderMax + "." + keys.get(index).resultName()));
            orderLatestOn.add(eq(periodPath, orderMax + "." + periodColumn));
            orderLatestOn.add(eq(orderPath, orderMax + "." + maximumOrder));
            latestJoins.add(new VisualQueryDefinition.Join(null, orderMax, orderMax,
                VisualQueryDefinition.JoinKind.INNER, new JoinCondition.Group(JoinLogicalOperator.AND, orderLatestOn)));

            var lineLatestOn = new ArrayList<JoinCondition>();
            for (int index = 0; index < groups.size(); index++)
                lineLatestOn.add(eq(groups.get(index), lineMax + "." + keys.get(index).resultName()));
            lineLatestOn.add(eq(periodPath, lineMax + "." + periodColumn));
            lineLatestOn.add(eq(orderPath, lineMax + "." + recordingOrderColumn));
            lineLatestOn.add(eq(linePath, lineMax + "." + maximumLine));
            latestJoins.add(new VisualQueryDefinition.Join(null, lineMax, lineMax,
                VisualQueryDefinition.JoinKind.INNER, new JoinCondition.Group(JoinLogicalOperator.AND, lineLatestOn)));

            String recorderTypeColumn = prefix + "recorder_type";
            String recorderIdColumn = prefix + "recorder_id";
            String lineNumberColumn = prefix + "line_no";
            latestFields.add(field(row + ".recorder.typeCode", recorderTypeColumn));
            latestFields.add(field(row + ".recorder.id", recorderIdColumn));
            latestFields.add(field(orderPath, recordingOrderColumn));
            latestFields.add(field(linePath, lineNumberColumn));
            columnNames.put("recorder.typeCode", recorderTypeColumn);
            columnNames.put("recorder.id", recorderIdColumn);
            columnNames.put("recordingOrder", recordingOrderColumn);
            columnNames.put("lineNumber", lineNumberColumn);
            columns.add(new Column("recorder.typeCode", recorderTypeColumn, String.class, Role.RECORDER));
            columns.add(new Column("recorder.id", recorderIdColumn, Long.class, Role.RECORDER));
            columns.add(new Column("recordingOrder", recordingOrderColumn, Long.class, Role.RECORDER));
            columns.add(new Column("lineNumber", lineNumberColumn, Integer.class, Role.RECORDER));
        }
        var after = new ArrayList<FilterNode>();
        filterPaths.put(period.name(), periodPath);
        spec.afterSlice().forEach(value -> after.add(condition(filterPaths.get(value.field()), value.operator(), value.value(), value.value().getClass())));
        parameterFilters.stream().filter(SliceParameterFilter::afterSlice).forEach(value -> after.add(parameterCondition(
            filterPaths.get(value.field()), value, value.field().equals(period.name()) ? LocalDateTime.class : resourceTypes.get(value.field()), source.resources().stream().anyMatch(field -> field.field().equals(value.field()) && field.reference()))));
        var latestDefinition = withParameters(definition(descriptor.entityName(), row, latestFields, latestJoins, List.of(), List.of(), and(after)), parameterFilters.isEmpty() ? List.of() : declarations);
        generatedCtes.add(new VisualQueryPackage.Cte(latest, latestDefinition, selectedRecorders));
        return new Fragment(generatedCtes, columns, columnNames, latest, prefix + "out",
            source.entityNames(), new NameContext(occupied));
    }

    private static Set<String> names(String prefix, RegisterSliceSource source) {
        Set<String> result = new LinkedHashSet<>();
        for (String suffix : List.of("mx", "orderMax", "lineMax", "latest", "row", "group", "out",
                "period", "max_period", "max_order", "max_line", "recording_order", "line_no",
                "recorder_type", "recorder_id")) result.add(prefix + suffix);
        for (int index = 0; index < source.dimensions().size(); index++) {
            result.add(prefix + "d" + index); result.add(prefix + "target" + index);
        }
        for (int index = 0; index < source.resources().size(); index++) {
            result.add(prefix + "r" + index); result.add(prefix + "resourceTarget" + index);
        }
        return result;
    }
    private static FilterNode condition(String path, SliceSpec.Operator operator, Object value, Class<?> type) {
        FilterDataType dataType = type.isEnum() ? FilterDataType.ENUM
            : type == LocalDate.class || type == LocalDateTime.class ? FilterDataType.DATE
            : type == Boolean.class || type == boolean.class ? FilterDataType.BOOLEAN
            : Number.class.isAssignableFrom(SliceSpecValidator.boxed(type)) ? FilterDataType.NUMBER : FilterDataType.TEXT;
        String text = value instanceof Enum<?> enumeration ? enumeration.name() : value.toString();
        return new FilterConditionNode(new FilterCondition(path, FilterOperator.valueOf(operator.name()), text, null, dataType));
    }

    private static FilterNode parameterCondition(String path, SliceParameterFilter filter, Class<?> type, boolean reference) {
        FilterDataType dataType = type.isEnum() ? FilterDataType.ENUM
            : type == LocalDate.class || type == LocalDateTime.class ? FilterDataType.DATE
            : type == Boolean.class || type == boolean.class ? FilterDataType.BOOLEAN
            : Number.class.isAssignableFrom(SliceSpecValidator.boxed(type)) ? FilterDataType.NUMBER : FilterDataType.TEXT;
        return new FilterConditionNode(new FilterCondition(path, FilterOperator.valueOf(filter.operator().name()), ":" + (reference ? "sliceId_" : "") + filter.parameter(), null, dataType));
    }
    private static FilterNode and(List<FilterNode> nodes) {
        return nodes.isEmpty() ? null : nodes.size() == 1 ? nodes.getFirst() : new FilterGroup(LogicalOperator.AND, nodes);
    }
    private static VisualQueryDefinition.SelectField field(String path, String name) { return new VisualQueryDefinition.SelectField(path, name); }
    private static VisualQueryDefinition withParameters(VisualQueryDefinition query,
                                                       List<VisualQueryDefinition.Parameter> parameters) {
        return new VisualQueryDefinition(query.version(), query.entityName(), query.entityAlias(), query.selectFields(),
            query.joins(), query.groupBy(), query.aggregates(), query.expressions(), query.having(), query.where(),
            parameters, query.orders(), query.subqueries());
    }
    private static JoinCondition eq(String left, String right) { return new JoinCondition.Predicate(left, JoinCondition.Operator.EQ, right); }
    private static VisualQueryDefinition definition(String entity, String alias, List<VisualQueryDefinition.SelectField> fields,
        List<VisualQueryDefinition.Join> joins, List<String> groups, List<VisualQueryDefinition.Aggregate> aggregates, FilterNode where) {
        return new VisualQueryDefinition(VisualQueryDefinition.CURRENT_VERSION, entity, alias, fields, joins, groups,
            aggregates, List.of(), null, where, List.of(), List.of());
    }
}
