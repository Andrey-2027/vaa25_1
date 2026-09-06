package org.ipro.reportstudio.query;

import org.ipro.filtergrid.projection.CompiledFilter;
import org.ipro.filtergrid.projection.ProjectionFilterCompiler;

import org.ipro.reportstudio.data.QueryField;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.dom.ReportQuerySource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.ipro.reportstudio.param.EntityParamRefresher;
import org.ipro.reportstudio.param.ReportContext;
import org.ipro.reportstudio.param.ReportParamResolver;
import org.ipro.reportstudio.param.ResolvedParams;
import org.ipro.reportstudio.run.ReportExecutionService;
import org.ipro.filtergrid.filter.FilterNode;
import org.ipro.filtergrid.filter.FilterTreeJson;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.core.JsonProcessingException;

/** Общая подготовка запроса отчёта перед preview и production execution. */
public class ReportQueryAssemblyService {
    private final ReportQueryGuard guard;
    private final ReportParamResolver paramResolver;
    private final EntityParamRefresher refresher;
    private final QueryBuilderMetadataCatalog visualCatalog;

    public ReportQueryAssemblyService(ReportQueryGuard guard, ReportParamResolver paramResolver,
                                      EntityParamRefresher refresher) {
        this(guard, paramResolver, refresher, null);
    }

    public ReportQueryAssemblyService(ReportQueryGuard guard, ReportParamResolver paramResolver,
                                      EntityParamRefresher refresher, QueryBuilderMetadataCatalog visualCatalog) {
        this.guard = guard;
        this.paramResolver = paramResolver;
        this.refresher = refresher;
        this.visualCatalog = visualCatalog;
    }

    public ReportQueryAssembler assemble(ReportTemplate template, ReportContext context,
                                         Map<String, Object> formValues) {
        Set<String> names = ReportQueryGuard.parameterNamesOf(template.getParams());
        VisualCompile visual = resolveExecutableJpql(template);
        String executableJpql = visual.jpql();
        // Внутренние bindings визуального запроса (WHERE-литералы, HAVING :params)
        // — часть контракта компилятора: guard должен знать их имена, executor — значения.
        java.util.Set<String> allNames = new java.util.LinkedHashSet<>(names);
        allNames.addAll(visual.bindings().keySet());
        Map<String, Class<?>> visualEntityClasses = visualParameterEntityClasses(template);
        GuardResult checked = guard.guard(executableJpql, allNames, visualEntityClasses);
        if (checked == null) {
            // Mockito/test doubles and lightweight adapters may only stub the two-argument overload.
            checked = guard.guard(executableJpql, allNames);
        }
        if (!checked.allowed()) {
            throw new IllegalArgumentException("Отказ: " + String.join("; ", checked.errors()));
        }
        ResolvedParams params = paramResolver.resolve(template.getParams(), context, formValues);
        if (!params.ok()) {
            throw new IllegalArgumentException("Не удалось заполнить параметры: "
                    + String.join("; ", params.errors()));
        }
        Map<String, Object> bindings = new HashMap<>(params.bindings());
        ServiceParams.bindings(context, refresher).forEach(bindings::putIfAbsent);
        // Компиляторные bindings (WHERE-литералы) идут первыми; resolveVisualParameterBindings
        // затем заполняет значения объявленных visual-параметров (в т.ч. из HAVING).
        bindings.putAll(visual.bindings());
        bindings.putAll(resolveVisualParameterBindings(template, executableJpql, bindings));
        String jpql = applyVisualFilter(executableJpql, checked, template, bindings);
        jpql = OrderByApplier.withOrderBy(jpql,
                ReportExecutionService.groupFieldsOf(template),
                ReportExecutionService.ordersOf(template));
        return new ReportQueryAssembler(jpql, bindings, checked.selectFields(), checked.warnings());
    }

    /**
     * Runtime spike: добавляет уже параметризованный предикат к проверенному JPQL.
     * Имена и выражения предиката должны быть сформированы отдельным allow-list адаптером.
     */
    public ReportQueryAssembler assembleWithPredicate(String jpql, Set<String> parameterNames,
                                                       Map<String, Object> bindings,
                                                       ReportTemplate template, String predicate,
                                                       Map<String, Object> predicateBindings) {
        GuardResult checked = guard.guard(jpql, parameterNames);
        if (!checked.allowed()) {
            throw new IllegalArgumentException("Отказ: " + String.join("; ", checked.errors()));
        }
        Map<String, Object> allBindings = new HashMap<>(bindings == null ? Map.of() : bindings);
        if (predicateBindings != null) allBindings.putAll(predicateBindings);
        String filtered = WhereClauseApplier.apply(jpql, predicate);
        String ordered = OrderByApplier.withOrderBy(filtered,
                ReportExecutionService.groupFieldsOf(template),
                ReportExecutionService.ordersOf(template));
        return new ReportQueryAssembler(ordered, allBindings, checked.selectFields(), checked.warnings());
    }

    /** Сборка preview с уже подготовленными тестовыми bindings. */
    public ReportQueryAssembler assembleForPreview(String jpql, Set<String> parameterNames,
                                                    Map<String, Object> bindings,
                                                    ReportTemplate template) {
        return assembleForPreview(jpql, parameterNames, bindings, template,
                ReportExecutionService.groupFieldsOf(template),
                ReportExecutionService.ordersOf(template));
    }

    /**
     * Сборка preview с ЯВНЫМИ группировками/сортировками: вызывающий (редактор)
     * уже отфильтровал пути с «чужими» алиасами — группировки и сортировки
     * прежнего текста не должны ломать freshly построенный запрос.
     */
    public ReportQueryAssembler assembleForPreview(String jpql, Set<String> parameterNames,
                                                    Map<String, Object> bindings, ReportTemplate template,
                                                    List<String> groupFields, List<OrderByApplier.OrderSpec> orders) {
        GuardResult checked = guard.guard(jpql, parameterNames);
        if (!checked.allowed()) {
            throw new IllegalArgumentException("Отказ: " + String.join("; ", checked.errors()));
        }
        Map<String, Object> allBindings = new HashMap<>(bindings == null ? Map.of() : bindings);
        String ordered = OrderByApplier.withOrderBy(jpql, groupFields, orders);
        return new ReportQueryAssembler(ordered, allBindings, checked.selectFields(), checked.warnings());
    }

    private Map<String, Class<?>> visualParameterEntityClasses(ReportTemplate template) {
        if (template.getVisualQueryJson() == null) return Map.of();
        String json = template.getVisualQueryJson();
        VisualQueryDefinition definition = json.contains("\"ctes\"")
                ? new VisualQueryPackageJsonCodec().read(json).main()
                : new VisualQueryDefinitionJsonCodec().read(json);
        Map<String, Class<?>> result = new HashMap<>();
        for (VisualQueryDefinition.Parameter parameter : definition.parameters()) {
            // Visual-параметры пока не несут отдельного имени Java-класса;
            // entity bindings остаются ответственностью обычных ReportParam.
        }
        return result;
    }

    private Map<String, Object> resolveVisualParameterBindings(ReportTemplate template, String jpql, Map<String, Object> bindings) {
        if (template.getVisualQueryJson() == null) return Map.of();
        String json = template.getVisualQueryJson();
        VisualQueryDefinition definition = json.contains("\"ctes\"")
                ? new VisualQueryPackageJsonCodec().read(json).main()
                : new VisualQueryDefinitionJsonCodec().read(json);
        Set<String> declared = definition.parameters().stream().map(VisualQueryDefinition.Parameter::name).collect(java.util.stream.Collectors.toSet());
        Set<String> used = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(":([A-Za-z_][A-Za-z0-9_]*)").matcher(jpql);
        while (matcher.find()) used.add(matcher.group(1));
        Map<String, Object> supplied = new HashMap<>();
        bindings.forEach((name, value) -> { if (declared.contains(name)) supplied.put(name, value); });
        return VisualQueryParameterRuntime.resolve(definition.parameters(), supplied);
    }

    /** Результат компиляции visual query: итоговый JPQL + внутренние bindings (WHERE/HAVING). */
    record VisualCompile(String jpql, Map<String, Object> bindings) {
        static final VisualCompile EMPTY = new VisualCompile(null, Map.of());
    }

    private VisualCompile resolveExecutableJpql(ReportTemplate template) {
        if (template.getQuerySource() != ReportQuerySource.VISUAL) {
            return new VisualCompile(template.getJpql(), Map.of());
        }
        try {
            String json = template.getVisualQueryJson();
            ReportQueryAssembler compiled;
            if (json != null && json.contains("\"ctes\"")) {
                compiled = VisualQueryCompiler.compile(new VisualQueryPackageJsonCodec().read(json), visualCatalog);
            } else {
                VisualQueryDefinition definition = new VisualQueryDefinitionJsonCodec().read(json);
                compiled = VisualQueryCompiler.compile(definition, visualCatalog);
            }
            return new VisualCompile(compiled.jpql(), compiled.bindings());
        } catch (Exception error) {
            throw new IllegalArgumentException("Некорректный визуальный запрос: " + error.getMessage(), error);
        }
    }

    private String applyVisualFilter(String jpql, GuardResult checked, ReportTemplate template,
                                     Map<String, Object> bindings) {
        if (template.getVisualFilterJson() == null) return jpql;
        try {
            FilterNode filter = FilterTreeJson.read(new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(template.getVisualFilterJson()));
            CompiledFilter compiled = new ProjectionFilterCompiler(
                    new QueryFieldFilterFieldResolver(checked.selectFields())).compile(filter, bindings);
            bindings.putAll(compiled.bindings());
            return WhereClauseApplier.apply(jpql, compiled.predicate());
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Некорректный JSON визуального фильтра", error);
        }
    }
}
