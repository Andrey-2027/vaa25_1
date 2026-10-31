package org.ipro.query;

import org.ipro.filtergrid.projection.CompiledFilter;
import org.ipro.filtergrid.projection.ProjectionFilterCompiler;

import org.ipro.report.data.QueryField;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.ipro.query.param.EntityParamRefresher;
import org.ipro.query.ReportContext;
import org.ipro.query.param.ReportParamResolver;
import org.ipro.query.param.ResolvedParams;
import org.ipro.query.model.QueryDefinition;
import org.ipro.query.model.QueryParameter;
import org.ipro.query.model.QuerySort;
import org.ipro.query.model.QuerySource;
import org.ipro.filtergrid.filter.FilterNode;
import org.ipro.filtergrid.filter.FilterTreeJson;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * Общая подготовка запроса перед preview и production execution.
 *
 * <p>Основной вход — нейтральное {@link QueryDefinition}. Переход из отчётного
 * шаблона живёт в отдельном адаптере; поэтому backend не читает бэнды и не
 * зависит от UDR-модели.</p>
 */
public class ReportQueryAssemblyService {
    private final ReportQueryGuard guard;
    private final ReportParamResolver paramResolver;
    private final EntityParamRefresher refresher;
    private final QueryBuilderMetadataCatalog visualCatalog;
    private ReportQueryLanguage queryLanguage;

    public void useQueryLanguage(ReportQueryLanguage language) {
        queryLanguage = language;
    }

    /** Re-check the complete predicate, retaining the source's public presentation schema. */
    public ReportQueryAssembler appendPredicate(ReportQueryAssembler source, String predicate,
                                                Map<String, Object> predicateBindings) {
        var bindings = new LinkedHashMap<>(source.bindings());
        for (var entry : predicateBindings.entrySet()) {
            if (bindings.containsKey(entry.getKey())) {
                throw new IllegalArgumentException("Повторное имя биндинга: " + entry.getKey());
            }
            bindings.put(entry.getKey(), entry.getValue());
        }
        String jpql = WhereClauseApplier.apply(source.jpql(), predicate);
        var names = new LinkedHashSet<>(JpqlParameters.names(source.jpql()));
        names.addAll(bindings.keySet());
        var checked = guard.guard(jpql, names);
        if (!checked.allowed()) {
            throw new IllegalArgumentException("Отказ: " + String.join("; ", checked.errors()));
        }
        return new ReportQueryAssembler(jpql, bindings, source.fields(), checked.warnings(),
                source.presentations());
    }

    public ReportQueryAssemblyService(ReportQueryGuard guard, ReportParamResolver paramResolver,
                                      EntityParamRefresher refresher) {
        this(guard, paramResolver, refresher, null);
    }

    public ReportQueryAssemblyService(ReportQueryGuard guard, ReportParamResolver paramResolver,
                                      EntityParamRefresher refresher,
                                      QueryBuilderMetadataCatalog visualCatalog) {
        this.guard = guard;
        this.paramResolver = paramResolver;
        this.refresher = refresher;
        this.visualCatalog = visualCatalog;
    }

    // === Нейтральный вход ===

    public ReportQueryAssembler assemble(QueryDefinition definition, ReportContext context,
                                         Map<String, Object> formValues) {
        return assemble(definition, context, formValues, List.of(), List.of());
    }

    /**
     * Сборка запроса с явными требованиями к порядку строк. Групповые поля и
     * сортировки приходят от отчётного адаптера; query backend не читает бэнды.
     */
    public ReportQueryAssembler assemble(QueryDefinition definition, ReportContext context,
                                         Map<String, Object> formValues,
                                         List<String> groupFields, List<QuerySort> sorts) {
        Set<String> names = ReportQueryGuard.queryParameterNamesOf(definition.parameters());
        VisualCompile visual = resolveExecutableJpql(definition);
        String executableJpql = visual.jpql();
        // Внутренние bindings визуального запроса (WHERE-литералы, HAVING :params)
        // — часть контракта компилятора: guard должен знать их имена, executor — значения.
        java.util.Set<String> allNames = new java.util.LinkedHashSet<>(names);
        allNames.addAll(visual.bindings().keySet());
        Map<String, Class<?>> visualEntityClasses = visualParameterEntityClasses(definition);
        GuardResult checked = guard.guard(executableJpql, allNames, visualEntityClasses);
        if (checked == null) {
            // Mockito/test doubles and lightweight adapters may only stub the two-argument overload.
            checked = guard.guard(executableJpql, allNames);
        }
        if (!checked.allowed()) {
            throw new IllegalArgumentException("Отказ: " + String.join("; ", checked.errors()));
        }
        ResolvedParams params = paramResolver.resolve(definition.parameters(), context, formValues);
        if (!params.ok()) {
            throw new IllegalArgumentException("Не удалось заполнить параметры: "
                    + String.join("; ", params.errors()));
        }
        Map<String, Object> bindings = new HashMap<>(params.bindings());
        ServiceParams.bindings(context, refresher).forEach(bindings::putIfAbsent);
        // Компиляторные bindings (WHERE-литералы) идут первыми; resolveVisualParameterBindings
        // затем заполняет значения объявленных visual-параметров (в т.ч. из HAVING).
        visual.bindings().forEach((name, value) -> {
            if (!VisualQueryCompiler.PENDING_PARAM.equals(value) || name.matches("visualFilter_[0-9]+"))
                bindings.put(name, value);
        });
        refreshSliceEntities(definition, bindings);
        expandIdentityBindings(bindings, visual.bindings().keySet());
        bindings.putAll(resolveVisualParameterBindings(definition, executableJpql, bindings));
        String jpql = applyVisualFilter(executableJpql, checked, definition, bindings, visual.fields());
        requirePresentationOrdering(visual.presentations(), groupFields, sorts);
        jpql = OrderByApplier.withOrderBy(jpql, groupFields, orderSpecs(sorts));
        return new ReportQueryAssembler(jpql, bindings,
                visual.presentations().isEmpty() ? checked.selectFields() : visual.fields(),
                checked.warnings(), visual.presentations());
    }

    /** Describe the same executable query as assemble(), without resolving values or executing SQL. */
    public ReportQueryAssembler describe(QueryDefinition definition) {
        var visual = resolveExecutableJpql(definition);
        var names = new LinkedHashSet<>(ReportQueryGuard.queryParameterNamesOf(definition.parameters()));
        names.addAll(visual.bindings().keySet());
        var checked = guard.guard(visual.jpql(), names, visualParameterEntityClasses(definition));
        if (!checked.allowed()) {
            throw new IllegalArgumentException("Отказ: " + String.join("; ", checked.errors()));
        }
        return new ReportQueryAssembler(visual.jpql(), visual.bindings(),
                visual.presentations().isEmpty() ? checked.selectFields() : visual.fields(),
                checked.warnings(), visual.presentations());
    }

    /**
     * Runtime spike: добавляет уже параметризованный предикат к проверенному JPQL.
     * Имена и выражения предиката должны быть сформированы отдельным allow-list адаптером.
     */
    public ReportQueryAssembler assembleWithPredicate(String jpql, Set<String> parameterNames,
                                                       Map<String, Object> bindings,
                                                       List<String> groupFields, List<QuerySort> sorts,
                                                       String predicate,
                                                       Map<String, Object> predicateBindings) {
        GuardResult checked = guard.guard(jpql, parameterNames);
        if (!checked.allowed()) {
            throw new IllegalArgumentException("Отказ: " + String.join("; ", checked.errors()));
        }
        Map<String, Object> allBindings = new HashMap<>(bindings == null ? Map.of() : bindings);
        if (predicateBindings != null) allBindings.putAll(predicateBindings);
        String filtered = WhereClauseApplier.apply(jpql, predicate);
        String ordered = OrderByApplier.withOrderBy(filtered, groupFields, orderSpecs(sorts));
        return new ReportQueryAssembler(ordered, allBindings, checked.selectFields(), checked.warnings());
    }

    /** Сборка preview с уже подготовленными тестовыми bindings. */
    public ReportQueryAssembler assembleForPreview(String jpql, Set<String> parameterNames,
                                                    Map<String, Object> bindings) {
        return assembleForPreview(jpql, parameterNames, bindings, (QueryDefinition) null,
                List.of(), List.of());
    }

    public ReportQueryAssembler assembleForPreview(String jpql, Set<String> parameterNames,
                                                    Map<String, Object> bindings,
                                                    QueryDefinition definition) {
        return assembleForPreview(jpql, parameterNames, bindings, definition, List.of(), List.of());
    }

    /**
     * Сборка preview с ЯВНЫМИ группировками/сортировками: вызывающий (редактор)
     * уже отфильтровал пути с «чужими» алиасами — группировки и сортировки
     * прежнего текста не должны ломать freshly построенный запрос.
     */
    public ReportQueryAssembler assembleForPreview(String jpql, Set<String> parameterNames,
                                                    Map<String, Object> bindings,
                                                    QueryDefinition definition,
                                                    List<String> groupFields, List<QuerySort> sorts) {
        VisualCompile visual = definition != null && definition.source() == QuerySource.VISUAL
                ? resolveExecutableJpql(definition) : new VisualCompile(jpql, Map.of(), List.of());
        jpql = visual.jpql();
        var names = new LinkedHashSet<>(parameterNames);
        names.addAll(visual.bindings().keySet());
        GuardResult checked = guard.guard(jpql, names);
        if (!checked.allowed()) {
            throw new IllegalArgumentException("Отказ: " + String.join("; ", checked.errors()));
        }
        Map<String, Object> allBindings = new HashMap<>(bindings == null ? Map.of() : bindings);
        visual.bindings().forEach((name, value) -> {
            if (!VisualQueryCompiler.PENDING_PARAM.equals(value)) allBindings.put(name, value);
        });
        refreshSliceEntities(definition, allBindings);
        expandIdentityBindings(allBindings, visual.bindings().keySet());
        if (definition != null && definition.source() == QuerySource.VISUAL)
            allBindings.putAll(resolveVisualParameterBindings(definition, jpql, allBindings));
        requirePresentationOrdering(visual.presentations(), groupFields, sorts);
        String ordered = OrderByApplier.withOrderBy(jpql, groupFields, orderSpecs(sorts));
        return new ReportQueryAssembler(ordered, allBindings,
                visual.presentations().isEmpty() ? checked.selectFields() : visual.fields(),
                checked.warnings(), visual.presentations());
    }

    // === Внутренние шаги ===

    private Map<String, Class<?>> visualParameterEntityClasses(QueryDefinition definition) {
        Map<String, Class<?>> result = new HashMap<>();
        for (QueryParameter parameter : definition.parameters()) {
            String className = parameter.entityClass();
            if (className == null || className.isBlank()) continue;
            try {
                result.put(parameter.name(), Class.forName(className, false,
                        Thread.currentThread().getContextClassLoader()));
            } catch (ClassNotFoundException error) {
                throw new IllegalArgumentException("Неизвестный entityClass параметра "
                        + parameter.name() + ": " + className, error);
            }
        }
        return result;
    }

    private Map<String, Object> resolveVisualParameterBindings(QueryDefinition definition,
                                                               String jpql,
                                                               Map<String, Object> bindings) {
        if (definition.source() != QuerySource.VISUAL) return Map.of();
        if (definition.visualQueryJson() == null) return Map.of();
        String json = definition.visualQueryJson();
        VisualQueryDefinition visual = json.contains("\"ctes\"")
                ? new VisualQueryPackageJsonCodec().read(json).main()
                : new VisualQueryDefinitionJsonCodec().read(json);
        Set<String> declared = visual.parameters().stream()
                .map(VisualQueryDefinition.Parameter::name)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> used = new LinkedHashSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile(":([A-Za-z_][A-Za-z0-9_]*)").matcher(jpql);
        while (matcher.find()) used.add(matcher.group(1));
        Map<String, Object> supplied = new HashMap<>();
        bindings.forEach((name, value) -> {
            if (declared.contains(name)) supplied.put(name, value);
        });
        return VisualQueryParameterRuntime.resolve(visual.parameters(), supplied);
    }

    /** Результат компиляции visual query: итоговый JPQL + внутренние bindings (WHERE/HAVING). */
    record VisualCompile(String jpql, Map<String, Object> bindings, List<QueryField> fields,
                         QueryPresentationPlan presentations) {
        VisualCompile(String jpql, Map<String, Object> bindings, List<QueryField> fields) {
            this(jpql, bindings, fields, QueryPresentationPlan.EMPTY);
        }

        static final VisualCompile EMPTY = new VisualCompile(null, Map.of(), List.of());
    }

    private VisualCompile resolveExecutableJpql(QueryDefinition definition) {
        if (definition.source() != QuerySource.VISUAL) {
            if (queryLanguage != null && ReportQueryLanguage.containsExtensions(definition.jpql())) {
                var compiled = queryLanguage.parse(definition.jpql()).compiled();
                return new VisualCompile(compiled.jpql(), compiled.bindings(), compiled.fields(),
                        compiled.presentations());
            }
            return new VisualCompile(definition.jpql(), Map.of(), List.of());
        }
        try {
            String json = definition.visualQueryJson();
            ReportQueryAssembler compiled;
            if (json != null && json.contains("\"ctes\"")) {
                compiled = VisualQueryCompiler.compile(new VisualQueryPackageJsonCodec().read(json),
                        visualCatalog);
            } else {
                VisualQueryDefinition visual = new VisualQueryDefinitionJsonCodec().read(json);
                compiled = VisualQueryCompiler.compile(visual, visualCatalog);
            }
            return new VisualCompile(compiled.jpql(), compiled.bindings(), compiled.fields(),
                    compiled.presentations());
        } catch (Exception error) {
            throw new IllegalArgumentException("Некорректный визуальный запрос: "
                    + error.getMessage(), error);
        }
    }

    private void refreshSliceEntities(QueryDefinition definition, Map<String, Object> bindings) {
        if (queryLanguage == null || definition == null || definition.source() != QuerySource.VISUAL
                || definition.visualQueryJson() == null) return;
        for (var parameter : queryLanguage.entityParameters(definition.visualQueryJson()).entrySet()) {
            Object value = bindings.get(parameter.getKey());
            if (!parameter.getValue().isInstance(value)
                    || !(value instanceof org.ipro.identity.IdentifiableEntity entity)
                    || entity.getId() == null)
                throw new IllegalArgumentException("Выберите доступный элемент параметра: "
                        + parameter.getKey());
            Object fresh = refresher.refresh(parameter.getValue(), entity.getId());
            if (fresh == null)
                throw new IllegalArgumentException("Элемент параметра недоступен: "
                        + parameter.getKey());
            bindings.put(parameter.getKey(), fresh);
        }
    }

    static void expandIdentityBindings(Map<String, Object> bindings, Set<String> names) {
        for (var name : names) if (name.startsWith("sliceId_")) {
            Object value = bindings.get(name.substring("sliceId_".length()));
            if (value instanceof org.ipro.identity.IdentifiableEntity entity) value = entity.getId();
            if (!(value instanceof Number))
                throw new IllegalArgumentException("Выберите значение ссылочного параметра: "
                        + name.substring("sliceId_".length()));
            bindings.put(name, ((Number) value).longValue());
        }
    }

    private static void requirePresentationOrdering(QueryPresentationPlan plan, List<String> groups,
                                                    List<QuerySort> sorts) {
        for (var column : plan.columns())
            if (groups.contains(column.name())
                    || sorts.stream().anyMatch(sort -> sort.columnName().equals(column.name())))
                throw new IllegalArgumentException(
                        "Группировка и сортировка по представлению пока не поддерживаются: "
                                + column.name());
    }

    private static List<OrderByApplier.OrderSpec> orderSpecs(List<QuerySort> sorts) {
        return sorts == null ? List.of() : sorts.stream()
                .map(sort -> new OrderByApplier.OrderSpec(sort.columnName(), sort.descending()))
                .toList();
    }

    private String applyVisualFilter(String jpql, GuardResult checked, QueryDefinition definition,
                                     Map<String, Object> bindings, List<QueryField> visualFields) {
        if (definition.visualFilterJson() == null) return jpql;
        try {
            FilterNode filter = FilterTreeJson.read(new ObjectMapper()
                    .readTree(definition.visualFilterJson()));
            var allowed = new QueryFieldFilterFieldResolver(checked.selectFields());
            org.ipro.filtergrid.filter.FilterFieldResolver executionFields = path -> {
                var field = allowed.resolve(path);
                var projection = visualFields.stream().filter(column -> column.name().equals(path))
                        .findFirst()
                        .orElseGet(() -> checked.selectFields().stream()
                                .filter(column -> column.name().equals(path)).findFirst().orElseThrow());
                String expression = projection.expression();
                if (expression == null || expression.isBlank()) return field;
                if (!expression.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*"))
                    throw new IllegalArgumentException("WHERE-фильтр требует scalar path: " + path);
                return new org.ipro.filtergrid.filter.FilterFieldResolver.ResolvedFilterField(
                        expression, field.label(), field.javaType(), field.dataType(),
                        field.filterEnabled());
            };
            var paths = new HashMap<String,
                    org.ipro.filtergrid.filter.FilterFieldResolver.ResolvedFilterField>();
            FilterNode executableFilter = executionFilter(filter, executionFields, paths);
            CompiledFilter compiled = new ProjectionFilterCompiler(path ->
                    java.util.Objects.requireNonNull(paths.get(path)))
                    .compile(executableFilter, bindings);
            Map<String, String> names = new HashMap<>();
            int index = 0;
            for (var binding : compiled.bindings().entrySet()) {
                String name = binding.getKey();
                while (bindings.containsKey(name)) name = "reportFilter_" + (++index);
                names.put(binding.getKey(), name);
                bindings.put(name, binding.getValue());
            }
            String predicate = compiled.predicate();
            if (predicate != null) predicate = java.util.regex.Pattern
                    .compile(":([A-Za-z_][A-Za-z0-9_]*)")
                    .matcher(predicate)
                    .replaceAll(match -> ":" + names.getOrDefault(match.group(1), match.group(1)));
            return WhereClauseApplier.apply(jpql, predicate);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Некорректный JSON визуального фильтра", error);
        }
    }

    private static FilterNode executionFilter(FilterNode node,
            org.ipro.filtergrid.filter.FilterFieldResolver resolver,
            Map<String, org.ipro.filtergrid.filter.FilterFieldResolver.ResolvedFilterField> paths) {
        if (node instanceof org.ipro.filtergrid.filter.FilterConditionNode conditionNode) {
            var condition = conditionNode.condition();
            var field = resolver.resolve(condition.path());
            paths.put(field.path(), field);
            return new org.ipro.filtergrid.filter.FilterConditionNode(
                    new org.ipro.filtergrid.filter.FilterCondition(
                            field.path(), condition.operator(), condition.value(),
                            condition.valueTo(), condition.dataType(), condition.values()));
        }
        var group = (org.ipro.filtergrid.filter.FilterGroup) node;
        return new org.ipro.filtergrid.filter.FilterGroup(group.operator(),
                group.children().stream()
                        .map(child -> executionFilter(child, resolver, paths)).toList());
    }
}
