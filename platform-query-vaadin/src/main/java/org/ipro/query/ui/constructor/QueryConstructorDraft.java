package org.ipro.query.ui.constructor;

import org.ipro.filtergrid.filter.FilterCondition;
import org.ipro.filtergrid.filter.FilterConditionNode;
import org.ipro.filtergrid.filter.FilterGroup;
import org.ipro.filtergrid.filter.FilterNode;
import org.ipro.report.data.QueryField;
import org.ipro.query.CaseBranch;
import org.ipro.query.CaseCondition;
import org.ipro.query.JoinCondition;
import org.ipro.query.JoinLogicalOperator;
import org.ipro.query.QueryBuilderMetadataCatalog;
import org.ipro.query.ReportQueryAssembler;
import org.ipro.query.VisualQueryCompiler;
import org.ipro.query.VisualQueryDefinition;
import org.ipro.query.VisualQueryExpression;
import org.ipro.query.VisualQueryOrder;
import org.ipro.query.VisualQueryPackage;
import org.ipro.register.RecorderRef;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Рабочая модель конструктора запроса в стиле 1С — единый черновик для всех
 * вкладок. Текст итогового JPQL собирается {@link VisualQueryCompiler} из
 * {@link #definition()}; текст — источник истины, определение хранится как
 * черновик для повторного открытия конструктора.
 *
 * <p>Правила, унаследованные от конструктора 1С:</p>
 * <ul>
 *   <li>первая добавленная таблица становится FROM, остальные подключаются
 *       JOIN'ами — по ассоциации существующей таблицы или как независимая
 *       связь с условием ON;</li>
 *   <li>поле, взятое сквозь ассоциацию («Платформа.Код»), автоматически
 *       создаёт промежуточные JOIN;</li>
 *   <li>агрегат замещает обычное поле SELECT, сохраняя его псевдоним
 *       (МАКСИМУМ(Дата) КАК Дата), при удалении агрегата поле возвращается;</li>
 *   <li>при наличии хотя бы одного агрегата все не-агрегатные поля SELECT
 *       автоматически попадают в GROUP BY.</li>
 * </ul>
 */
public final class QueryConstructorDraft {

    /** Таблица черновика: корень FROM или цель JOIN. */
    public record TableRef(String alias, QueryBuilderMetadataCatalog.Entity entity,
                           boolean root, VisualQueryDefinition.Join join) { }

    /**
     * Результат компиляции черновика: готовый текст, колонки и bindings
     * (значения WHERE-условий) либо сообщение об ошибке. Bindings нужны
     * редактору запроса для тестовых значений :visualFilter_*.
     */
    public record Compiled(String jpql, List<QueryField> fields, Map<String, Object> bindings, String error) {
        public boolean ok() { return jpql != null; }
    }

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final int MAX_CHAIN_DEPTH = 3;

    private QueryBuilderMetadataCatalog catalog;
    private List<QueryBuilderMetadataCatalog.Entity> roots = List.of();
    private String entityName;
    private String entityAlias;
    private final List<VisualQueryDefinition.SelectField> selections = new ArrayList<>();
    private final List<VisualQueryDefinition.Join> joins = new ArrayList<>();
    private final List<String> groupingPaths = new ArrayList<>();
    private final List<VisualQueryDefinition.Aggregate> aggregates = new ArrayList<>();
    private final List<VisualQueryDefinition.Expression> expressions = new ArrayList<>();
    private final List<VisualQueryOrder> orders = new ArrayList<>();
    private final List<VisualQueryDefinition.HavingCondition> havingConditions = new ArrayList<>();
    private JoinLogicalOperator havingOperator = JoinLogicalOperator.AND;
    private FilterNode where;
    private List<VisualQueryDefinition.Parameter> declaredParameters = List.of();
    /** Подзапросы активного этапа: условия WHERE ссылаются на них по имени (sub1, sub2, …). */
    private List<VisualQueryDefinition.Subquery> subqueries = List.of();
    private final LinkedHashMap<String, VisualQueryDefinition> packageStages = new LinkedHashMap<>();
    private final LinkedHashMap<String, List<RecorderRef>> packageStageRecorders = new LinkedHashMap<>();
    private final LinkedHashMap<String, org.ipro.query.register.RegisterSliceReportTemplates.ConstructorSlice> slices = new LinkedHashMap<>();
    private final LinkedHashMap<String, org.ipro.query.ui.list.ConstructorList> lists = new LinkedHashMap<>();
    private String activeStage = "main";
    /** Поле SELECT, замещённое агрегатом — для восстановления при удалении агрегата. */
    private final Map<String, VisualQueryDefinition.SelectField> replacedByAggregate = new LinkedHashMap<>();

    // === Каталог (RLS-фильтрованный allow-list) ===

    public void setCatalog(QueryBuilderMetadataCatalog catalog) {
        this.catalog = catalog;
        this.roots = catalog == null ? List.of() : catalog.roots();
    }

    public QueryBuilderMetadataCatalog catalog() { return catalog; }

    /** Корни каталога (кэшируются при setCatalog — roots() пересобирает метаданные каждый вызов). */
    public List<QueryBuilderMetadataCatalog.Entity> roots() { return roots; }

    public QueryBuilderMetadataCatalog.Entity entity(String name) {
        if (name == null) return null;
        var regular = roots.stream().filter(e -> e.entityName().equals(name)).findFirst().orElse(null);
        if (regular != null) return regular;
        // Виртуальный CTE: на этапе main (или следующем этапе) предыдущие временные
        // таблицы доступны как источники — JOIN к ним есть в определении main.
        return availableCteEntities().stream()
                .filter(candidate -> candidate.entityName().equals(name))
                .findFirst().orElse(null);
    }

    public QueryBuilderMetadataCatalog.Entity entityByType(Class<?> type) {
        if (type == null) return null;
        return roots.stream().filter(e -> e.javaType().equals(type)).findFirst().orElse(null);
    }

    // === Корень ===

    public boolean hasRoot() { return entityName != null && !entityName.isBlank(); }

    public QueryBuilderMetadataCatalog.Entity root() {
        return hasRoot() ? entity(entityName) : null;
    }

    public String activeStage() { return activeStage; }

    public List<String> stageNames() { return List.copyOf(packageStages.keySet()); }

    public List<String> visibleStageNames() {
        var hidden = sliceStages();
        hidden.addAll(listStages());
        return packageStages.keySet().stream().filter(name -> !hidden.contains(name)).toList();
    }

    public List<org.ipro.query.register.RegisterSliceReportTemplates.ConstructorSlice> slices() {
        return List.copyOf(slices.values());
    }

    public List<org.ipro.query.ui.list.ConstructorList> lists() { return List.copyOf(lists.values()); }

    public void setLists(List<org.ipro.query.ui.list.ConstructorList> sources) {
        lists.clear(); sources.forEach(source -> lists.put(source.name(), source));
    }

    public String addConstructorList(org.ipro.query.ui.list.ConstructorList source, String ownerAlias, String resultAlias) {
        if (source.sourceQuery() != null) return addConstructorListByField(source, ownerAlias, resultAlias);
        if (!isMainStage()) throw new IllegalArgumentException("Добавляйте список в итоговый запрос");
        var owner = tables().stream().filter(t -> t.alias().equals(ownerAlias)
                && t.entity().entityName().equals(source.parentEntity())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Не найден родитель списка"));
        if (source.stages().stream().anyMatch(s -> packageStages.containsKey(s.name())))
            throw new IllegalArgumentException("Повторное имя списка");
        source.stages().forEach(s -> packageStages.put(s.name(), s.definition()));
        lists.put(source.name(), source);
        String alias = addCteTable(source.name());
        var join = joins.stream().filter(j -> j.alias().equals(alias)).findFirst().orElseThrow();
        joins.set(joins.indexOf(join), new VisualQueryDefinition.Join(null, source.name(), alias,
                VisualQueryDefinition.JoinKind.LEFT, new JoinCondition.Predicate(alias + ".key", JoinCondition.Operator.EQ, owner.alias() + ".id")));
        addSelection(alias + ".value", resultAlias);
        return alias;
    }

    /** The target is a physical field path (alias.field), not a SELECT result alias. */
    public String addConstructorListByField(org.ipro.query.ui.list.ConstructorList source, String targetPath, String resultAlias) {
        if (!isMainStage() || !hasRoot()) throw new IllegalArgumentException("Добавляйте список в итоговый запрос с выбранным источником");
        if (targetPath == null || !targetPath.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+"))
            throw new IllegalArgumentException("Выберите поле связи основного запроса");
        if (source.stages().stream().anyMatch(s -> packageStages.containsKey(s.name())))
            throw new IllegalArgumentException("Повторное имя этапа списка");
        String alias = uniqueAlias("l" + source.name().replaceFirst("^list", ""));
        validateListTarget(alias, targetPath);
        var join = new VisualQueryDefinition.Join(null, source.name(), alias, VisualQueryDefinition.JoinKind.LEFT,
                new JoinCondition.Predicate(alias + ".key", JoinCondition.Operator.EQ, targetPath));
        // Validate the prospective package before mutating the draft.
        var current = packageDefinition();
        if (current == null) throw new IllegalArgumentException("Выберите поля основного запроса");
        var stages = new ArrayList<>(current.ctes()); stages.addAll(source.stages());
        var main = current.main(); var candidateJoins = new ArrayList<>(main.joins()); candidateJoins.add(join);
        var parameters = new LinkedHashMap<String, VisualQueryDefinition.Parameter>();
        main.parameters().forEach(p -> parameters.put(p.name(), p));
        for (var stage : source.stages()) for (var parameter : stage.definition().parameters()) {
            var previous = parameters.putIfAbsent(parameter.name(), parameter);
            if (previous != null && !previous.equals(parameter)) throw new IllegalArgumentException("Несовместимый параметр источника списка: " + parameter.name());
        }
        var candidate = new VisualQueryDefinition(main.version(), main.entityName(), main.entityAlias(), main.selectFields(),
                candidateJoins, main.groupBy(), main.aggregates(), main.expressions(), main.having(), main.where(),
                List.copyOf(parameters.values()), main.orders(), main.subqueries());
        VisualQueryCompiler.compile(new VisualQueryPackage(stages, candidate), catalog);
        source.stages().forEach(s -> { packageStages.put(s.name(), s.definition()); setStageRecorders(s.name(), s.selectedRecorders()); });
        lists.put(source.name(), source); joins.add(join);
        declaredParameters = List.copyOf(parameters.values());
        addSelection(alias + ".value", resultAlias);
        return alias;
    }

    public void updateConstructorList(String name, org.ipro.query.ui.list.ConstructorList replacement) {
        var original = lists.get(name);
        if (original == null || !name.equals(replacement.name())
                || !original.entity().fields().getFirst().javaType().equals(replacement.entity().fields().getFirst().javaType()))
            throw new IllegalArgumentException("Изменение структуры требует добавить новый список");
        var oldNames = original.stages().stream().map(VisualQueryPackage.Cte::name).collect(Collectors.toSet());
        if (replacement.stages().stream().anyMatch(s -> packageStages.containsKey(s.name()) && !oldNames.contains(s.name())))
            throw new IllegalArgumentException("Имя этапа источника списка уже занято");
        var pack = packageDefinition();
        var stages = new ArrayList<VisualQueryPackage.Cte>();
        boolean inserted = false;
        for (var stage : pack.ctes()) {
            if (oldNames.contains(stage.name())) {
                if (!inserted) { stages.addAll(replacement.stages()); inserted = true; }
            } else stages.add(stage);
        }
        var parameters = new LinkedHashMap<String, VisualQueryDefinition.Parameter>();
        declaredParameters.forEach(p -> parameters.put(p.name(), p));
        for (var stage : replacement.stages()) for (var parameter : stage.definition().parameters()) {
            var previous = parameters.putIfAbsent(parameter.name(), parameter);
            if (previous != null && !previous.equals(parameter)) throw new IllegalArgumentException("Несовместимый параметр источника списка: " + parameter.name());
        }
        VisualQueryCompiler.compile(new VisualQueryPackage(stages, pack.main()), catalog);
        var ordered = new LinkedHashMap<String, VisualQueryDefinition>();
        stages.forEach(s -> ordered.put(s.name(), s.definition()));
        if (packageStages.containsKey("main")) ordered.put("main", pack.main());
        packageStages.clear(); packageStages.putAll(ordered);
        oldNames.forEach(packageStageRecorders::remove);
        replacement.stages().forEach(s -> setStageRecorders(s.name(), s.selectedRecorders()));
        declaredParameters = List.copyOf(parameters.values());
        lists.put(name, replacement);
    }

    private Set<String> listStages() {
        return lists.values().stream().flatMap(s -> s.stages().stream()).map(VisualQueryPackage.Cte::name).collect(Collectors.toSet());
    }

    public void pruneUnusedLists() {
        if (lists.isEmpty()) return;
        packageStages.put(activeStage, definition());
        var unused = lists.values().stream().filter(source -> packageStages.values().stream().filter(Objects::nonNull)
                .noneMatch(d -> d.entityName().equals(source.name()) || d.joins().stream().anyMatch(j -> j.independent() && j.sourcePath().equals(source.name())))).toList();
        for (var source : unused) {
            lists.remove(source.name());
            source.stages().forEach(s -> { packageStages.remove(s.name()); packageStageRecorders.remove(s.name()); });
        }
    }

    public List<String> parameterNames() {
        return declaredParameters.stream().map(VisualQueryDefinition.Parameter::name).toList();
    }

    public java.util.Set<String> occupiedNames() {
        var names = new LinkedHashSet<>(packageStages.keySet());
        tables().forEach(table -> names.add(table.alias()));
        roots.forEach(root -> names.add(root.entityName()));
        return names;
    }

    public void setSlices(List<org.ipro.query.register.RegisterSliceReportTemplates.ConstructorSlice> sources) {
        slices.clear(); sources.forEach(source -> slices.put(source.name(), source));
    }

    public String addSlice(org.ipro.query.register.RegisterSliceReportTemplates.ConstructorSlice source) {
        if (!isMainStage()) throw new IllegalArgumentException("Добавляйте срез в итоговый запрос.");
        checkSliceParameters(source);
        for (var stage : source.stages()) {
            if (packageStages.containsKey(stage.name())) throw new IllegalArgumentException("Повторное имя источника среза.");
        }
        for (var stage : source.stages()) {
            packageStages.put(stage.name(), stage.definition()); setStageRecorders(stage.name(), stage.selectedRecorders());
        }
        slices.put(source.name(), source);
        String alias = addCteTable(source.name());
        ensureSliceParameters(source);
        return alias;
    }

    /** Updates generated stages without changing field paths, table aliases or the main query. */
    public void updateSlice(String name, org.ipro.query.register.RegisterSliceReportTemplates.ConstructorSlice replacement) {
        var original = slices.get(name);
        if (original == null || !replacement.name().equals(name)
                || !original.registerEntity().equals(replacement.registerEntity()))
            throw new IllegalArgumentException("Изменение типа источника требует добавить новый срез.");
        var names = original.stages().stream().map(org.ipro.query.VisualQueryPackage.Cte::name).toList();
        if (!names.equals(replacement.stages().stream().map(org.ipro.query.VisualQueryPackage.Cte::name).toList()))
            throw new IllegalArgumentException("Изменение структуры источника требует добавить новый срез.");
        checkSliceParameters(replacement);
        for (var stage : replacement.stages()) {
            packageStages.put(stage.name(), stage.definition()); setStageRecorders(stage.name(), stage.selectedRecorders());
        }
        slices.put(name, replacement);
        ensureSliceParameters(replacement);
        pruneSliceParameters(original);
    }

    private void pruneSliceParameters(org.ipro.query.register.RegisterSliceReportTemplates.ConstructorSlice source) {
        var candidates = sliceParameters(source).stream().map(VisualQueryDefinition.Parameter::name).collect(Collectors.toSet());
        declaredParameters = declaredParameters.stream().filter(parameter -> !candidates.contains(parameter.name())
            || parameterStillReferenced(parameter.name())
            || (!parameter.name().startsWith("sliceId_") && parameterStillReferenced("sliceId_" + parameter.name()))).toList();
    }

    private boolean parameterStillReferenced(String name) {
        var definitions = new ArrayList<>(packageStages.values()); definitions.add(definition());
        for (var definition : definitions) {
            if (definition == null) continue;
            try {
                var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                    new org.ipro.query.VisualQueryDefinitionJsonCodec().write(definition));
                ((com.fasterxml.jackson.databind.node.ObjectNode) node).remove("parameters");
                if (jsonReferencesParameter(node, name)) return true;
            } catch (Exception unsupported) { return true; }
        }
        return false;
    }

    private static boolean jsonReferencesParameter(com.fasterxml.jackson.databind.JsonNode node, String name) {
        if (node.isTextual()) {
            String value = node.asText();
            return value.equals(name) || Pattern.compile(":" + Pattern.quote(name) + "(?![A-Za-z0-9_])").matcher(value).find();
        }
        for (var child : node) if (jsonReferencesParameter(child, name)) return true;
        return false;
    }

    private List<VisualQueryDefinition.Parameter> sliceParameters(org.ipro.query.register.RegisterSliceReportTemplates.ConstructorSlice source) {
        var values = new LinkedHashMap<String, VisualQueryDefinition.Parameter>();
        for (var parameter : source.stages().getFirst().definition().parameters()) {
            values.put(parameter.name(), parameter);
            if (parameter.name().startsWith("sliceId_")) {
                String name = parameter.name().substring(8);
                values.put(name, new VisualQueryDefinition.Parameter(name, "ENTITY", true, null));
            }
        }
        return List.copyOf(values.values());
    }
    private void checkSliceParameters(org.ipro.query.register.RegisterSliceReportTemplates.ConstructorSlice source) {
        for (var filter : source.parameterFilters()) {
            Class<?> target = sliceReferenceType(source, filter.field());
            for (var previous : slices.values()) {
                if (previous.name().equals(source.name())) continue;
                for (var used : previous.parameterFilters()) if (used.parameter().equals(filter.parameter())
                        && sliceReferenceType(previous, used.field()) != target)
                    throw new IllegalArgumentException("Несовместимый тип ссылочного параметра: " + filter.parameter());
            }
        }
        for (var value : sliceParameters(source)) if (declaredParameters.stream().anyMatch(existing -> existing.name().equals(value.name()) && !existing.dataType().equals(value.dataType())))
            throw new IllegalArgumentException("Параметр «" + value.name() + "» уже имеет другой тип");
    }
    private static Class<?> sliceReferenceType(org.ipro.query.register.RegisterSliceReportTemplates.ConstructorSlice source, String field) {
        return source.entity().fields().stream().filter(value -> value.name().equals(field)).map(value -> value.referenceType()).filter(Objects::nonNull).findFirst().orElse(null);
    }

    private void ensureSliceParameters(org.ipro.query.register.RegisterSliceReportTemplates.ConstructorSlice source) {
        var values = new LinkedHashMap<String, VisualQueryDefinition.Parameter>();
        declaredParameters.forEach(value -> values.put(value.name(), value));
        sliceParameters(source).forEach(value -> values.put(value.name(), value));
        declaredParameters = List.copyOf(values.values());
    }

    private void ensureSliceParameter(String name) {
        checkSliceParameter(name);
        var parameters = new ArrayList<>(declaredParameters);
        var existing = parameters.stream().filter(parameter -> parameter.name().equals(name)).findFirst();
        if (existing.isEmpty()) parameters.add(new VisualQueryDefinition.Parameter(name, "DATETIME", true, null));
        declaredParameters = List.copyOf(parameters);
    }

    private void checkSliceParameter(String name) {
        if (declaredParameters.stream().anyMatch(parameter -> parameter.name().equals(name) && !"DATETIME".equals(parameter.dataType())))
            throw new IllegalArgumentException("Параметр «" + name + "» уже имеет другой тип.");
    }

    public void pruneUnusedSlices() {
        if (slices.isEmpty()) return;
        packageStages.put(activeStage, definition());
        Set<String> hidden = sliceStages();
        // A table can be selected before SELECT fields; definition() is still null then.
        var unused = slices.values().stream()
            .filter(source -> !source.name().equals(entityName) && joins.stream()
                .noneMatch(join -> join.independent() && join.sourcePath().equals(source.name())))
            .filter(source -> packageStages.entrySet().stream()
            .filter(entry -> !hidden.contains(entry.getKey())).map(Map.Entry::getValue).filter(Objects::nonNull)
            .noneMatch(definition -> definition.entityName().equals(source.name()) || definition.joins().stream()
                .anyMatch(join -> join.independent() && join.sourcePath().equals(source.name())))).toList();
        for (var source : unused) {
            slices.remove(source.name());
            source.stages().forEach(stage -> { packageStages.remove(stage.name()); packageStageRecorders.remove(stage.name()); });
            pruneSliceParameters(source);
        }
    }

    private Set<String> sliceStages() {
        return slices.values().stream().flatMap(source -> source.stages().stream()).map(VisualQueryPackage.Cte::name)
            .collect(Collectors.toSet());
    }

    /** Retains the typed candidate selection attached to a generated recorder CTE. */
    public void setStageRecorders(String stage, List<RecorderRef> recorders) {
        if (stage == null || stage.isBlank()) throw new IllegalArgumentException("Этап обязателен");
        List<RecorderRef> copy = List.copyOf(recorders == null ? List.of() : recorders);
        if (new java.util.HashSet<>(copy).size() != copy.size())
            throw new IllegalArgumentException("Повторная ссылка регистратора в этапе: " + stage);
        if (copy.isEmpty()) packageStageRecorders.remove(stage);
        else packageStageRecorders.put(stage, copy);
    }

    /** Clears stages before loading a separate saved visual package. */
    public void clearPackageStages() {
        packageStages.clear();
        packageStageRecorders.clear();
        slices.clear();
        activeStage = "main";
        lists.clear();
    }

    public boolean isMainStage() { return "main".equals(activeStage); }

    public void switchStage(String stage) {
        if (stage == null || stage.isBlank()) throw new IllegalArgumentException("Этап обязателен");
        if (stage.equals(activeStage)) return;
        if (!activeStage.equals("main")) packageStages.put(activeStage, definitionOrEmpty());
        else packageStages.put("main", definitionOrEmpty());
        VisualQueryDefinition next = packageStages.get(stage);
        activeStage = stage;
        clear();
        if (next != null) load(next);
    }

    public void renameStage(String oldName, String newName) {
        if (oldName == null || newName == null || oldName.equals(newName)
                || "main".equals(oldName) || "main".equals(newName)
                || packageStages.containsKey(newName) || !packageStages.containsKey(oldName)) return;
        List<RecorderRef> recorders = packageStageRecorders.remove(oldName);
        if (recorders != null) packageStageRecorders.put(newName, recorders);
        LinkedHashMap<String, VisualQueryDefinition> copy = new LinkedHashMap<>();
        packageStages.forEach((key, definition) -> copy.put(key.equals(oldName) ? newName : key, definition));
        packageStages.clear();
        packageStages.putAll(copy);
        if (oldName.equals(activeStage)) activeStage = newName;
    }

    public String addStage() {
        String name = "tmp" + (packageStages.size() + 1);
        int suffix = 2;
        while (packageStages.containsKey(name)) name = "tmp" + (packageStages.size() + suffix++);
        if (!activeStage.equals("main")) packageStages.put(activeStage, definitionOrEmpty());
        else if (hasRoot()) packageStages.put("main", definitionOrEmpty());
        packageStages.putIfAbsent(name, null);
        packageStageRecorders.remove(name);
        activeStage = name;
        clear();
        return name;
    }

    public void removeStage(String stage) {
        if (stage == null || "main".equals(stage)) return;
        packageStages.remove(stage);
        packageStageRecorders.remove(stage);
        var removed = new LinkedHashSet<String>();
        packageStages.entrySet().removeIf(entry -> {
            boolean remove = entry.getValue() != null && entry.getValue().entityName().equals(stage);
            if (remove) removed.add(entry.getKey());
            return remove;
        });
        removed.forEach(packageStageRecorders::remove);
        if (activeStage.equals(stage)) { activeStage = "main"; clear(); VisualQueryDefinition main = packageStages.get("main"); if (main != null) load(main); }
    }

    public VisualQueryPackage packageDefinition() {
        if (activeStage.equals("main")) packageStages.put("main", definitionOrEmpty());
        else packageStages.put(activeStage, definitionOrEmpty());
        VisualQueryDefinition main = packageStages.get("main");
        if (main == null) return null;
        List<VisualQueryPackage.Cte> ctes = new ArrayList<>();
        Set<String> available = new LinkedHashSet<>();
        for (var entry : packageStages.entrySet()) if (!entry.getKey().equals("main") && entry.getValue() != null) {
            VisualQueryDefinition d = entry.getValue();
            VisualQueryPackage.Source source = available.contains(d.entityName())
                    ? new VisualQueryPackage.CteSource(d.entityAlias(), d.entityName())
                    : new VisualQueryPackage.EntitySource(d.entityName(), d.entityAlias());
            ctes.add(new VisualQueryPackage.Cte(entry.getKey(), source, d,
                packageStageRecorders.getOrDefault(entry.getKey(), List.of())));
            available.add(entry.getKey());
        }
        VisualQueryPackage.Source mainSource = available.contains(main.entityName())
                ? new VisualQueryPackage.CteSource(main.entityAlias(), main.entityName())
                : new VisualQueryPackage.EntitySource(main.entityName(), main.entityAlias());
        // Main source is currently represented by its definition; source is retained
        // for future package consumers and validated here.
        if (!mainSource.alias().equals(main.entityAlias())) throw new IllegalStateException("Некорректный alias итогового этапа");
        return new VisualQueryPackage(ctes, main);
    }

    private VisualQueryDefinition definitionOrEmpty() { return definition(); }

    public String rootAlias() { return entityAlias; }

    /** Устанавливает корневую таблицу и сбрасывает всё построенное поверх неё. */
    public void setRoot(QueryBuilderMetadataCatalog.Entity entity) {
        if (entity == null) return;
        entityName = entity.entityName();
        entityAlias = uniqueAlias(lowerCamel(entity.entityName()));
        selections.clear();
        joins.clear();
        groupingPaths.clear();
        aggregates.clear();
        expressions.clear();
        orders.clear();
        havingConditions.clear();
        havingOperator = JoinLogicalOperator.AND;
        where = null;
        replacedByAggregate.clear();
    }

    // === Таблицы ===

    public List<QueryBuilderMetadataCatalog.Entity> availableCteEntities() {
        List<QueryBuilderMetadataCatalog.Entity> result = new ArrayList<>();
        for (var entry : packageStages.entrySet()) {
            if (!entry.getKey().equals("main") && !entry.getKey().equals(activeStage)
                    && entry.getValue() != null) {
                if (slices.containsKey(entry.getKey())) { result.add(slices.get(entry.getKey()).entity()); continue; }
                if (lists.containsKey(entry.getKey())) { result.add(lists.get(entry.getKey()).entity()); continue; }
                if (listStages().contains(entry.getKey())) continue;
                if (sliceStages().contains(entry.getKey())) continue;
                result.add(new QueryBuilderMetadataCatalog.Entity(entry.getKey(), Object.class,
                        entry.getValue().selectFields().stream().map(field ->
                                new QueryBuilderMetadataCatalog.Field(field.resultName(), field.resultName(), Object.class, false)).toList(),
                        List.of(), entry.getKey() + " (временная таблица)"));
            }
        }
        return result;
    }

    public void setCteRoot(String cteName, String alias) {
        var cte = packageStages.get(cteName);
        if (cte == null || "main".equals(cteName) || cteName.equals(activeStage)) return;
        clear();
        entityName = cteName;
        entityAlias = alias == null || alias.isBlank() ? "t" : alias;
    }

    /**
     * Добавляет виртуальный CTE как таблицу активного этапа: если корень ещё
     * не выбран — CTE становится корнем этапа; иначе — добавляется независимым
     * JOIN (условие ON задаётся на вкладке «Связи»). Уже построенный запрос
     * (корень, поля, связи) при этом не затирается.
     *
     * @return alias, под которым CTE доступен на этапе, или null
     */
    public String addCteTable(String cteName) {
        if (cteName == null || cteName.isBlank()) return null;
        if (!hasRoot()) {
            setCteRoot(cteName, slices.containsKey(cteName) ? "srez" : lists.containsKey(cteName) ? "l" : "t");
            return hasRoot() ? entityAlias : null;
        }
        if (entityName.equals(cteName)) return entityAlias;
        var existing = tables().stream()
                .filter(t -> t.entity().entityName().equals(cteName))
                .findFirst();
        if (existing.isPresent()) return existing.get().alias();
        if (lists.containsKey(cteName)) {
            var source = lists.get(cteName);
            if (source.sourceQuery() != null) throw new IllegalArgumentException("Для списка выберите поле связи основного запроса");
            var owner = tables().stream().filter(t -> t.entity().entityName().equals(source.parentEntity())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Не найден родитель списка"));
            String alias = uniqueAlias("l" + cteName.replaceFirst("^list", ""));
            return addJoin(new VisualQueryDefinition.Join(null, cteName, alias, VisualQueryDefinition.JoinKind.LEFT,
                    new JoinCondition.Predicate(alias + ".key", JoinCondition.Operator.EQ, owner.alias() + ".id"))).alias();
        }
        return addJoin(new VisualQueryDefinition.Join(null, cteName,
                slices.containsKey(cteName) ? "srez" : lowerCamel(cteName), VisualQueryDefinition.JoinKind.LEFT)).alias();
    }

    /** Alias, под которым виртуальный CTE доступен на активном этапе (корень или JOIN). */
    public String cteFieldAlias(String cteName) {
        if (cteName == null) return null;
        if (hasRoot() && entityName.equals(cteName)) return entityAlias;
        return tables().stream().filter(t -> t.entity().entityName().equals(cteName))
                .findFirst().map(TableRef::alias).orElse(null);
    }

    public List<TableRef> tables() {
        List<TableRef> result = new ArrayList<>();
        if (hasRoot()) {
            var entity = root();
            if (entity != null) result.add(new TableRef(entityAlias, entity, true, null));
        }
        for (var join : joins) {
            var target = entityOfJoin(join);
            if (target != null) result.add(new TableRef(join.alias(), target, false, join));
        }
        return result;
    }

    public boolean isEntitySelected(String name) {
        if (hasRoot() && entityName.equals(name)) return true;
        return tables().stream().anyMatch(t -> t.entity().entityName().equals(name));
    }

    public QueryBuilderMetadataCatalog.Entity entityOfJoin(VisualQueryDefinition.Join join) {
        if (join == null) return null;
        if (join.independent()) return entity(join.sourcePath());
        var source = entityForAlias(parentAliasOf(join));
        if (source == null) return null;
        var association = source.associations().stream()
                .filter(a -> a.name().equals(join.sourcePath()))
                .findFirst().orElse(null);
        return association == null ? null : entityByType(association.targetType());
    }

    public QueryBuilderMetadataCatalog.Entity entityForAlias(String alias) {
        if (alias == null) return null;
        if (alias.equals(entityAlias)) return root();
        return joins.stream().filter(j -> j.alias().equals(alias)).findFirst()
                .map(this::entityOfJoin).orElse(null);
    }

    /**
     * Добавляет таблицу: без корня — становится корнем; иначе связывается по
     * ассоциации существующей таблицы, а если ассоциации нет — добавляется
     * как независимый JOIN (условие ON задаётся на вкладке «Связи»).
     *
     * @return alias таблицы
     */
    public String addTable(QueryBuilderMetadataCatalog.Entity entity) {
        if (entity == null) return null;
        if (!hasRoot()) {
            setRoot(entity);
            return entityAlias;
        }
        if (entity.entityName().equals(entityName)) return entityAlias;
        var existing = tables().stream()
                .filter(t -> t.entity().entityName().equals(entity.entityName()))
                .findFirst();
        if (existing.isPresent()) return existing.get().alias();
        for (var table : tables()) {
            var association = table.entity().associations().stream()
                    .filter(a -> a.targetType() != null && a.targetType().equals(entity.javaType()))
                    .filter(a -> !joinExists(table.alias(), a.name()))
                    .findFirst();
            if (association.isPresent()) {
                var join = addJoin(new VisualQueryDefinition.Join(table.alias(), association.get().name(),
                        lowerCamel(entity.entityName()), VisualQueryDefinition.JoinKind.INNER));
                return join.alias();
            }
        }
        return addJoin(new VisualQueryDefinition.Join(null, entity.entityName(),
                lowerCamel(entity.entityName()), VisualQueryDefinition.JoinKind.INNER, null)).alias();
    }

    /**
     * Гарантирует таблицу для сущности и цепочку JOIN по ассоциациям.
     *
     * @return alias последнего звена цепочки
     */
    public String ensureChain(QueryBuilderMetadataCatalog.Entity baseEntity,
                              List<QueryBuilderMetadataCatalog.Association> chain) {
        if (baseEntity == null) return null;
        if (!hasRoot()) setRoot(baseEntity);
        String current = ensureTable(baseEntity);
        for (QueryBuilderMetadataCatalog.Association association : chain) {
            String alias = current;
            var existing = joins.stream()
                    .filter(j -> Objects.equals(parentAliasOf(j), alias))
                    .filter(j -> j.sourcePath().equals(association.name()))
                    .findFirst().orElse(null);
            if (existing != null) {
                current = existing.alias();
                continue;
            }
            var target = entityByType(association.targetType());
            if (target == null) {
                throw new IllegalArgumentException("Сущность связи «" + association.caption()
                        + "» недоступна: нет прав или её нет в каталоге.");
            }
            current = addJoin(new VisualQueryDefinition.Join(current, association.name(),
                    lowerCamel(target.entityName()), VisualQueryDefinition.JoinKind.INNER)).alias();
        }
        return current;
    }

    /** Добавляет поле, при необходимости создавая промежуточные JOIN. */
    public VisualQueryDefinition.SelectField addField(QueryBuilderMetadataCatalog.Entity baseEntity,
                                                      List<QueryBuilderMetadataCatalog.Association> chain,
                                                      String fieldName) {
        String alias = ensureChain(baseEntity, chain);
        return addSelection(alias + "." + fieldName, fieldName);
    }

    private String ensureTable(QueryBuilderMetadataCatalog.Entity entity) {
        if (entity.entityName().equals(entityName)) return entityAlias;
        var existing = tables().stream()
                .filter(t -> t.entity().entityName().equals(entity.entityName()))
                .findFirst();
        return existing.isPresent() ? existing.get().alias() : addTable(entity);
    }

    private boolean joinExists(String parentAlias, String sourcePath) {
        return joins.stream().anyMatch(j -> Objects.equals(parentAliasOf(j), parentAlias)
                && j.sourcePath().equals(sourcePath));
    }

    // === JOIN ===

    public List<VisualQueryDefinition.Join> joins() { return List.copyOf(joins); }

    /** Добавляет JOIN с гарантированно уникальным alias. */
    public VisualQueryDefinition.Join addJoin(VisualQueryDefinition.Join candidate) {
        if (candidate == null) return null;
        String alias = uniqueAlias(candidate.alias());
        var join = alias.equals(candidate.alias()) ? candidate
                : new VisualQueryDefinition.Join(candidate.parentAlias(), candidate.sourcePath(),
                        alias, candidate.kind(), candidate.on());
        joins.add(join);
        return join;
    }

    /** Изменяет alias, тип и условие ON существующего JOIN (alias переименовывается со всеми ссылками). */
    public void updateJoin(VisualQueryDefinition.Join join, String newAlias,
                           VisualQueryDefinition.JoinKind kind, JoinCondition on) {
        if (join == null) return;
        var current = joins.stream().filter(j -> j.alias().equals(join.alias())).findFirst().orElse(null);
        if (current == null) return;
        String alias = newAlias == null || newAlias.isBlank() ? current.alias() : sanitize(newAlias);
        if (!current.alias().equals(alias)) renameAlias(current.alias(), alias);
        var after = joins.stream().filter(j -> j.alias().equals(alias)).findFirst().orElse(null);
        if (after == null) return;
        joins.set(joins.indexOf(after), new VisualQueryDefinition.Join(after.parentAlias(), after.sourcePath(),
                after.alias(), kind, on));
    }

    /**
     * Каскадно удаляет таблицу вместе с её JOIN-веткой и всеми зависимыми
     * элементами. При удалении корня первый связанный JOIN становится новым
     * FROM (alias сохраняется — его поля остаются валидными).
     */
    public void removeTable(String alias) {
        if (alias == null || !hasRoot()) return;
        boolean wasRoot = alias.equals(entityAlias);
        if (!wasRoot && joins.stream().noneMatch(j -> j.alias().equals(alias))) return;

        if (wasRoot) {
            var promote = joins.stream().findFirst().orElse(null);
            if (promote != null && !promote.independent()) {
                var promotedEntity = entityOfJoin(promote);
                Set<String> dropped = new LinkedHashSet<>();
                dropped.add(alias);
                joins.forEach(join -> dropped.add(join.alias()));
                dropped.remove(promote.alias());
                joins.clear();
                dropReferences(dropped);
                if (promotedEntity == null) {
                    clear();
                    return;
                }
                entityName = promotedEntity.entityName();
                entityAlias = promote.alias();
                return;
            }
            clear();
            return;
        }

        Set<String> removed = new LinkedHashSet<>();
        removed.add(alias);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (var join : joins) {
                if (!removed.contains(join.alias()) && (removed.contains(parentAliasOf(join))
                        || removed.stream().anyMatch(a -> joinReferencesAlias(join.on(), a)))) {
                    removed.add(join.alias());
                    changed = true;
                }
            }
        }
        joins.removeIf(j -> removed.contains(j.alias()));
        dropReferences(removed);
    }

    // === Вычисляемые поля (CASE, арифметика, функции) ===

    public List<VisualQueryDefinition.Expression> expressions() { return List.copyOf(expressions); }

    /** Добавляет вычисляемое поле с уникальным псевдонимом. */
    public VisualQueryDefinition.Expression addExpression(String requestedName, VisualQueryExpression expression) {
        var expr = new VisualQueryDefinition.Expression(uniqueResultName(requestedName), expression);
        expressions.add(expr);
        return expr;
    }

    public void removeExpression(VisualQueryDefinition.Expression expression) {
        expressions.remove(expression);
    }

    /** Заменяет вычисляемое поле (правка из диалога): убирает старое, добавляет новое. */
    public VisualQueryDefinition.Expression replaceExpression(VisualQueryDefinition.Expression old,
                                                               String requestedName,
                                                               VisualQueryExpression expression) {
        expressions.remove(old);
        var expr = new VisualQueryDefinition.Expression(uniqueResultName(requestedName), expression);
        expressions.add(expr);
        return expr;
    }

    /** Карта alias → сущность выбранных таблиц — для проверки вычисляемых выражений. */
    public Map<String, QueryBuilderMetadataCatalog.Entity> aliasEntities() {
        Map<String, QueryBuilderMetadataCatalog.Entity> map = new LinkedHashMap<>();
        for (TableRef table : tables()) map.put(table.alias(), table.entity());
        return map;
    }

    private void dropReferences(Set<String> removedAliases) {
        selections.removeIf(s -> removedAliases.contains(firstSegment(s.path())));
        aggregates.removeIf(a -> removedAliases.contains(firstSegment(a.path())));
        orders.removeIf(o -> removedAliases.contains(firstSegment(o.path())));
        groupingPaths.removeIf(p -> removedAliases.contains(firstSegment(p)));
        expressions.removeIf(e -> expressionReferencesAlias(e.expression(), removedAliases));
        havingConditions.removeIf(c -> aggregates.stream().noneMatch(a -> a.resultName().equals(c.aggregateAlias())));
        replacedByAggregate.keySet().removeIf(p -> removedAliases.contains(firstSegment(p)));
        pruneWhere(removedAliases);
    }

    /** Переименовывает alias таблицы, обновляя все ссылки (пути полей, родителей JOIN, WHERE). */
    public void renameAlias(String oldAlias, String newAlias) {
        if (oldAlias == null || newAlias == null || oldAlias.equals(newAlias)) return;
        String sanitized = sanitize(newAlias);
        if (!IDENTIFIER.matcher(sanitized).matches() || aliasTaken(sanitized)) return;
        for (int i = 0; i < joins.size(); i++) {
            var join = joins.get(i);
            if (join.alias().equals(oldAlias)) {
                joins.set(i, new VisualQueryDefinition.Join(join.parentAlias(), join.sourcePath(),
                        sanitized, join.kind(), join.on()));
            } else if (oldAlias.equals(parentAliasOf(join))) {
                joins.set(i, new VisualQueryDefinition.Join(sanitized, join.sourcePath(),
                        join.alias(), join.kind(), join.on()));
            } else if (join.independent() && oldAlias.equals(join.sourcePath())) {
                joins.set(i, new VisualQueryDefinition.Join(join.parentAlias(), sanitized,
                        join.alias(), join.kind(), join.on()));
            }
        }
        if (oldAlias.equals(entityAlias)) {
            entityAlias = sanitized;
        }
        for (int i = 0; i < selections.size(); i++) {
            var selection = selections.get(i);
            if (oldAlias.equals(firstSegment(selection.path()))) {
                selections.set(i, new VisualQueryDefinition.SelectField(
                        renamePrefix(selection.path(), oldAlias, sanitized), selection.resultName()));
            }
        }
        groupingPaths.replaceAll(path -> oldAlias.equals(firstSegment(path))
                ? renamePrefix(path, oldAlias, sanitized) : path);
        for (int i = 0; i < aggregates.size(); i++) {
            var aggregate = aggregates.get(i);
            aggregates.set(i, aggregate.withPaths(oldAlias.equals(firstSegment(aggregate.path()))
                            ? renamePrefix(aggregate.path(), oldAlias, sanitized) : aggregate.path(),
                    oldAlias.equals(firstSegment(aggregate.orderPath()))
                            ? renamePrefix(aggregate.orderPath(), oldAlias, sanitized) : aggregate.orderPath()));
        }
        for (int i = 0; i < orders.size(); i++) {
            var order = orders.get(i);
            if (oldAlias.equals(firstSegment(order.path()))) {
                orders.set(i, new VisualQueryOrder(renamePrefix(order.path(), oldAlias, sanitized), order.direction()));
            }
        }
        for (int i = 0; i < expressions.size(); i++) {
            var expression = expressions.get(i);
            expressions.set(i, new VisualQueryDefinition.Expression(expression.resultName(),
                    renameInExpression(expression.expression(), oldAlias, sanitized)));
        }
        if (where != null) where = renameInFilter(where, oldAlias, sanitized);
        Map<String, VisualQueryDefinition.SelectField> renamed = new LinkedHashMap<>();
        replacedByAggregate.forEach((path, field) -> renamed.put(
                oldAlias.equals(firstSegment(path)) ? renamePrefix(path, oldAlias, sanitized) : path, field));
        replacedByAggregate.clear();
        replacedByAggregate.putAll(renamed);
    }

    private String parentAliasOf(VisualQueryDefinition.Join join) {
        return join.parentAlias() == null || join.parentAlias().isBlank() ? entityAlias : join.parentAlias();
    }

    // === Поля SELECT ===

    public List<VisualQueryDefinition.SelectField> selections() { return List.copyOf(selections); }

    public VisualQueryDefinition.SelectField addSelection(String path, String requestedName) {
        var field = new VisualQueryDefinition.SelectField(path, uniqueResultName(requestedName));
        selections.add(field);
        return field;
    }

    public void removeSelection(VisualQueryDefinition.SelectField field) {
        selections.remove(field);
        replacedByAggregate.remove(field.path());
        if (field.path().endsWith(".value") && selections.stream().noneMatch(f -> f.path().equals(field.path()))) {
            String alias = firstSegment(field.path());
            var join = joins.stream().filter(j -> j.alias().equals(alias) && lists.containsKey(j.sourcePath())).findFirst().orElse(null);
            if (join != null && !listValueReferenced(alias)) removeTable(alias);
        }
    }

    private boolean listValueReferenced(String alias) {
        String path = alias + ".value";
        return joins.stream().anyMatch(j -> !j.alias().equals(alias) && joinReferencesAlias(j.on(), alias))
                || selections.stream().anyMatch(f -> firstSegment(f.path()).equals(alias))
                || aggregates.stream().anyMatch(a -> firstSegment(a.path()).equals(alias) || firstSegment(a.orderPath()).equals(alias))
                || groupingPaths.contains(path) || orders.stream().anyMatch(o -> o.path().equals(path))
                || expressions.stream().anyMatch(e -> org.ipro.query.VisualQueryExpressionText.render(e.expression()).contains(alias + "."))
                || where != null;
    }

    private static boolean joinReferencesAlias(JoinCondition condition, String alias) {
        if (condition instanceof JoinCondition.Predicate predicate)
            return firstSegment(predicate.leftPath()).equals(alias) || firstSegment(predicate.rightPath()).equals(alias);
        return condition instanceof JoinCondition.Group group
                && group.children().stream().anyMatch(child -> joinReferencesAlias(child, alias));
    }

    private static void validateListTarget(String alias, String targetPath) {
        if (targetPath == null || !targetPath.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+"))
            throw new IllegalArgumentException("Выберите поле связи основного запроса");
        if (firstSegment(targetPath).equals(alias))
            throw new IllegalArgumentException("Список нельзя связать с самим собой");
    }

    /**
     * Переименовывает псевдоним поля SELECT. Имя должно быть корректным
     * идентификатором и не занятым другим полем/агрегатом; иначе изменение
     * молча игнорируется (после refresh вернётся прежнее значение).
     */
    public void renameResultName(VisualQueryDefinition.SelectField field, String newName) {
        if (field == null || newName == null) return;
        String sanitized = sanitize(newName);
        if (!IDENTIFIER.matcher(sanitized).matches() || sanitized.equals(field.resultName())) return;
        if (resultNameTaken(sanitized)) return;
        int index = selections.indexOf(field);
        if (index < 0) return;
        selections.set(index, new VisualQueryDefinition.SelectField(field.path(), sanitized));
    }

    public void clearSelections() {
        for (var field : List.copyOf(selections)) removeSelection(field);
        replacedByAggregate.clear();
    }

    public void updateConstructorList(String name, org.ipro.query.ui.list.ConstructorList replacement, String targetPath) {
        String alias = cteFieldAlias(name);
        validateListTarget(alias, targetPath);
        var current = joins.stream().filter(j -> j.alias().equals(alias)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Не найдена связь списка"));
        var on = new JoinCondition.Predicate(alias + ".key", JoinCondition.Operator.EQ, targetPath);
        var updated = new VisualQueryDefinition.Join(null, name, alias, VisualQueryDefinition.JoinKind.LEFT, on);
        int index = joins.indexOf(current);
        joins.set(index, updated);
        try { updateConstructorList(name, replacement); }
        catch (RuntimeException invalid) { joins.set(index, current); throw invalid; }
    }

    // === Группировка ===

    public List<String> groupingPaths() { return List.copyOf(groupingPaths); }

    public void addGrouping(String path) {
        if (path != null && !groupingPaths.contains(path)) groupingPaths.add(path);
    }

    public void removeGrouping(String path) { groupingPaths.remove(path); }

    public void clearGrouping() { groupingPaths.clear(); }

    /** Итоговый GROUP BY: явный список + (как в 1С) все не-агрегатные поля SELECT при наличии агрегатов. */
    public List<String> effectiveGrouping() {
        List<String> result = new ArrayList<>(groupingPaths);
        if (!aggregates.isEmpty()) {
            Set<String> aggregated = aggregates.stream()
                    .map(VisualQueryDefinition.Aggregate::path).collect(Collectors.toSet());
            for (var selection : selections) {
                if (!aggregated.contains(selection.path()) && !result.contains(selection.path())) {
                    result.add(selection.path());
                }
            }
        }
        return result;
    }

    public boolean isAutoGrouping(String path) {
        return !groupingPaths.contains(path) && effectiveGrouping().contains(path);
    }

    // === Агрегаты ===

    public List<VisualQueryDefinition.Aggregate> aggregates() { return List.copyOf(aggregates); }

    /**
     * Добавляет агрегат. Поле SELECT по тому же пути замещается (как в 1С):
     * обычное поле уходит из SELECT, агрегат занимает его псевдоним.
     *
     * @return агрегат или null, если агрегат по этому пути уже есть
     */
    public VisualQueryDefinition.Aggregate addAggregate(String function, String path, String requestedAlias) {
        if (aggregates.stream().anyMatch(a -> a.path().equals(path))) return null;
        var replaced = selections.stream().filter(s -> s.path().equals(path)).findFirst().orElse(null);
        if (replaced != null) {
            selections.remove(replaced);
            replacedByAggregate.put(path, replaced);
        }
        String alias = requestedAlias != null && !requestedAlias.isBlank() ? requestedAlias
                : replaced != null ? replaced.resultName()
                : function.toLowerCase(Locale.ROOT) + "_" + lastSegment(path);
        var aggregate = new VisualQueryDefinition.Aggregate(function, path, uniqueResultName(alias));
        aggregates.add(aggregate);
        return aggregate;
    }

    public void replaceAggregateFunction(VisualQueryDefinition.Aggregate aggregate, String function) {
        int index = aggregates.indexOf(aggregate);
        if (index < 0 || aggregate.function().equals(function)) return;
        aggregates.set(index, aggregate.withFunction(function));
    }

    /**
     * Переименовывает псевдоним агрегата (колонка в SELECT, например count(m.id) as cntMtr)
     * и синхронно обновляет ссылки на него в условиях HAVING. Имя должно быть корректным
     * идентификатором и свободным; иначе изменение молча игнорируется (после refresh
     * вернётся прежнее значение).
     */
    public void renameAggregate(VisualQueryDefinition.Aggregate aggregate, String newName) {
        if (aggregate == null || newName == null) return;
        String sanitized = sanitize(newName);
        if (!IDENTIFIER.matcher(sanitized).matches() || sanitized.equals(aggregate.resultName())) return;
        if (resultNameTaken(sanitized)) return;
        int index = aggregates.indexOf(aggregate);
        if (index < 0) return;
        aggregates.set(index, aggregate.withResultName(sanitized));
        for (int i = 0; i < havingConditions.size(); i++) {
            var condition = havingConditions.get(i);
            if (condition.aggregateAlias().equals(aggregate.resultName())) {
                havingConditions.set(i, new VisualQueryDefinition.HavingCondition(
                        sanitized, condition.operator(), condition.value()));
            }
        }
    }

    public void removeAggregate(VisualQueryDefinition.Aggregate aggregate) {
        aggregates.remove(aggregate);
        if (aggregates.stream().noneMatch(a -> a.path().equals(aggregate.path()))) {
            var restored = replacedByAggregate.remove(aggregate.path());
            if (restored != null) selections.add(restored);
        }
    }

    public void clearAggregates() {
        aggregates.clear();
        selections.addAll(replacedByAggregate.values());
        replacedByAggregate.clear();
        if (!aggregates.isEmpty() || !havingConditions.isEmpty()) clearHaving();
    }

    // === Порядок ===

    public List<VisualQueryOrder> orders() { return List.copyOf(orders); }

    public void addOrder(String path, VisualQueryOrder.Direction direction) {
        if (orders.stream().anyMatch(o -> o.path().equals(path))) return;
        orders.add(new VisualQueryOrder(path, direction == null ? VisualQueryOrder.Direction.ASC : direction));
    }

    public void replaceOrderDirection(VisualQueryOrder order, VisualQueryOrder.Direction direction) {
        int index = orders.indexOf(order);
        if (index >= 0 && direction != null) orders.set(index, new VisualQueryOrder(order.path(), direction));
    }

    public void removeOrder(VisualQueryOrder order) { orders.remove(order); }

    public void clearOrders() { orders.clear(); }

    // === Итоги (HAVING) ===

    public JoinLogicalOperator havingOperator() { return havingOperator; }

    public void setHavingOperator(JoinLogicalOperator operator) {
        havingOperator = operator == null ? JoinLogicalOperator.AND : operator;
    }

    public List<VisualQueryDefinition.HavingCondition> havingConditions() { return List.copyOf(havingConditions); }

    public boolean addHavingCondition(String aggregateAlias, VisualQueryDefinition.HavingOperator operator,
                                      VisualQueryDefinition.HavingValue value) {
        var condition = new VisualQueryDefinition.HavingCondition(aggregateAlias, operator, value);
        if (havingConditions.contains(condition)) return false;
        havingConditions.add(condition);
        return true;
    }

    public void replaceHavingCondition(VisualQueryDefinition.HavingCondition oldCondition,
                                       VisualQueryDefinition.HavingCondition newCondition) {
        int index = havingConditions.indexOf(oldCondition);
        if (index < 0) return;
        if (!havingConditions.contains(newCondition)) havingConditions.set(index, newCondition);
    }

    public void removeHavingCondition(VisualQueryDefinition.HavingCondition condition) {
        havingConditions.remove(condition);
    }

    public void clearHaving() {
        havingConditions.clear();
        havingOperator = JoinLogicalOperator.AND;
    }

    // === Условия (WHERE) ===

    public FilterNode where() { return where; }

    public void setWhere(FilterNode value) { where = value; }

    /** Убирает из WHERE условия по полям удалённых таблиц. */
    public void pruneWhere(Set<String> removedAliases) {
        if (where == null || removedAliases.isEmpty()) return;
        where = pruneNode(where, removedAliases);
    }

    private static FilterNode pruneNode(FilterNode node, Set<String> removedAliases) {
        if (node instanceof FilterConditionNode leaf) {
            return removedAliases.contains(firstSegment(leaf.condition().path())) ? null : node;
        }
        FilterGroup group = (FilterGroup) node;
        List<FilterNode> kept = group.children().stream()
                .map(child -> pruneNode(child, removedAliases))
                .filter(Objects::nonNull)
                .toList();
        return kept.isEmpty() ? null : new FilterGroup(group.operator(), kept);
    }

    private static FilterNode renameInFilter(FilterNode node, String oldAlias, String newAlias) {
        if (node instanceof FilterConditionNode leaf) {
            String path = leaf.condition().path();
            if (oldAlias.equals(firstSegment(path))) {
                FilterCondition renamed = new FilterCondition(renamePrefix(path, oldAlias, newAlias),
                        leaf.condition().operator(), leaf.condition().value(),
                        leaf.condition().valueTo(), leaf.condition().dataType());
                return new FilterConditionNode(renamed);
            }
            return node;
        }
        FilterGroup group = (FilterGroup) node;
        return new FilterGroup(group.operator(), group.children().stream()
                .map(child -> renameInFilter(child, oldAlias, newAlias)).toList());
    }

    // === Определение и компиляция ===

    /** Подзапросы активного этапа (для вкладки «Условия» и тестов). */
    public List<VisualQueryDefinition.Subquery> subqueries() { return List.copyOf(subqueries); }

    /** Определение запроса; null, пока не выбраны таблицы и поля. */
    public VisualQueryDefinition definition() {
        if (!hasRoot() || (selections.isEmpty() && aggregates.isEmpty() && expressions.isEmpty())) return null;
        VisualQueryDefinition.Having having = havingConditions.isEmpty() ? null
                : new VisualQueryDefinition.Having(havingOperator, havingConditions);
        return new VisualQueryDefinition(VisualQueryDefinition.CURRENT_VERSION, entityName, entityAlias,
                List.copyOf(selections), List.copyOf(joins), effectiveGrouping(), List.copyOf(aggregates),
                List.copyOf(expressions), having, where, declaredParameters, List.copyOf(orders), List.copyOf(subqueries));
    }

    /** Компилирует черновик; при ошибке возвращает message вместо текста. */
    public Compiled compile() {
        var definition = definition();
        if (definition == null) return new Compiled(null, List.of(), Map.of(), null);
        try {
            ReportQueryAssembler assembled = isMainStage() && !packageStages.isEmpty()
                    ? VisualQueryCompiler.compile(packageDefinition(), catalog)
                    : VisualQueryCompiler.compile(definition, catalog);
            return new Compiled(assembled.jpql(), assembled.fields(), assembled.bindings(), null);
        } catch (RuntimeException error) {
            return new Compiled(null, List.of(), Map.of(), message(error));
        }
    }

    /** Загружает сохранённое определение как черновик (для повторного открытия конструктора). */
    public void load(VisualQueryDefinition definition) {
        clear();
        if (definition == null) return;
        entityName = definition.entityName();
        entityAlias = definition.entityAlias();
        subqueries = definition.subqueries();
        selections.addAll(definition.selectFields());
        joins.addAll(definition.joins());
        groupingPaths.addAll(definition.groupBy());
        aggregates.addAll(definition.aggregates());
        expressions.addAll(definition.expressions());
        orders.addAll(definition.orders());
        if (definition.having() instanceof VisualQueryDefinition.Having having) {
            havingOperator = having.operator();
            havingConditions.addAll(having.conditions());
        }
        where = definition.where();
        declaredParameters = List.copyOf(definition.parameters());
    }

    public void clear() {
        entityName = null;
        entityAlias = null;
        selections.clear();
        joins.clear();
        groupingPaths.clear();
        aggregates.clear();
        expressions.clear();
        orders.clear();
        havingConditions.clear();
        havingOperator = JoinLogicalOperator.AND;
        where = null;
        subqueries = List.of();
        declaredParameters = List.of();
        replacedByAggregate.clear();
    }

    /** Ссылается ли AST выражения на алиас из набора (для каскадного удаления). */
    private static boolean expressionReferencesAlias(VisualQueryExpression expression, Set<String> aliases) {
        if (expression instanceof VisualQueryExpression.FieldRef field) {
            return aliases.contains(firstSegment(field.path()));
        }
        if (expression instanceof VisualQueryExpression.Binary binary) {
            return expressionReferencesAlias(binary.left(), aliases)
                    || expressionReferencesAlias(binary.right(), aliases);
        }
        if (expression instanceof VisualQueryExpression.FunctionCall function) {
            return function.arguments().stream().anyMatch(arg -> expressionReferencesAlias(arg, aliases));
        }
        if (expression instanceof VisualQueryExpression.Case caseExpr) {
            for (var branch : caseExpr.branches()) {
                if (expressionReferencesAlias(branch.result(), aliases)) return true;
                String leftPath = branch.condition() instanceof CaseCondition.Cmp cmp ? cmp.leftPath()
                        : ((CaseCondition.Between) branch.condition()).leftPath();
                if (aliases.contains(firstSegment(leftPath))) return true;
            }
            return caseExpr.elseResult() != null && expressionReferencesAlias(caseExpr.elseResult(), aliases);
        }
        return false;
    }

    /** Переименование алиасов внутри AST выражения (пути полей и CASE-условий). */
    private static VisualQueryExpression renameInExpression(VisualQueryExpression expression,
                                                            String oldAlias, String newAlias) {
        if (expression instanceof VisualQueryExpression.FieldRef field) {
            return oldAlias.equals(firstSegment(field.path()))
                    ? new VisualQueryExpression.FieldRef(renamePrefix(field.path(), oldAlias, newAlias))
                    : field;
        }
        if (expression instanceof VisualQueryExpression.Binary binary) {
            return new VisualQueryExpression.Binary(binary.operator(),
                    renameInExpression(binary.left(), oldAlias, newAlias),
                    renameInExpression(binary.right(), oldAlias, newAlias));
        }
        if (expression instanceof VisualQueryExpression.FunctionCall function) {
            return new VisualQueryExpression.FunctionCall(function.name(),
                    function.arguments().stream()
                            .map(arg -> renameInExpression(arg, oldAlias, newAlias)).toList());
        }
        if (expression instanceof VisualQueryExpression.Case caseExpr) {
            List<CaseBranch> branches = caseExpr.branches().stream()
                    .map(branch -> new CaseBranch(renameCaseCondition(branch.condition(), oldAlias, newAlias),
                            renameInExpression(branch.result(), oldAlias, newAlias)))
                    .toList();
            return new VisualQueryExpression.Case(branches,
                    caseExpr.elseResult() == null ? null
                            : renameInExpression(caseExpr.elseResult(), oldAlias, newAlias));
        }
        return expression;
    }

    private static CaseCondition renameCaseCondition(CaseCondition condition, String oldAlias, String newAlias) {
        if (condition instanceof CaseCondition.Cmp cmp) {
            return oldAlias.equals(firstSegment(cmp.leftPath()))
                    ? new CaseCondition.Cmp(renamePrefix(cmp.leftPath(), oldAlias, newAlias), cmp.op(), cmp.value())
                    : cmp;
        }
        CaseCondition.Between between = (CaseCondition.Between) condition;
        return oldAlias.equals(firstSegment(between.leftPath()))
                ? new CaseCondition.Between(renamePrefix(between.leftPath(), oldAlias, newAlias),
                        between.min(), between.max())
                : between;
    }

    // === Отображение и утилиты ===

    /** Отображение пути в стиле 1С: «Сущность.Поле» (по alias резолвится имя сущности). */
    public String displayPath(String path) {
        if (path == null || path.isBlank()) return "";
        String[] parts = path.split("\\.");
        if (parts.length < 2) return path;
        var entity = entityForAlias(parts[0]);
        String base = entity == null ? parts[0] : slices.containsKey(entity.entityName()) || lists.containsKey(entity.entityName()) ? entity.caption() : entity.entityName();
        return base + "." + String.join(".", java.util.Arrays.copyOfRange(parts, 1, parts.length));
    }

    /**
     * Русское имя поля по пути («s.codeSpec» → «Код спецификации»): подпись
     * последнего сегмента, промежуточные сегменты — проход по ассоциациям.
     * Для виртуальных CTE подпись — имя колонки; при невозможности резолва —
     * технический путь как fallback.
     */
    public String displayFieldName(String path) {
        if (path == null || path.isBlank()) return "";
        String[] segments = path.split("\\.");
        if (segments.length < 2) return path;
        QueryBuilderMetadataCatalog.Entity current = entityForAlias(segments[0]);
        if (current == null) return displayPath(path);
        for (int i = 1; i < segments.length - 1; i++) {
            String segment = segments[i];
            var step = current.associations().stream()
                    .filter(a -> a.name().equals(segment)).findFirst().orElse(null);
            if (step == null || step.targetType() == null) return displayPath(path);
            current = entityByType(step.targetType());
            if (current == null) return displayPath(path);
        }
        final QueryBuilderMetadataCatalog.Entity target = current;
        String name = segments[segments.length - 1];
        var field = target.fields().stream().filter(f -> f.name().equals(name)).findFirst().orElse(null);
        if (field != null && field.caption() != null && !field.caption().isBlank()) return field.caption();
        var association = target.associations().stream()
                .filter(a -> a.name().equals(name)).findFirst().orElse(null);
        if (association != null && association.caption() != null && !association.caption().isBlank()) {
            return association.caption();
        }
        if ("id".equals(name)) return "Идентификатор записи";
        return displayPath(path);
    }

    /** Глубина доступной цепочки ассоциаций для дерева каталога. */
    public static int maxChainDepth() { return MAX_CHAIN_DEPTH; }

    /** Сборка цепочки ассоциаций от узла-сущности вверх по дереву (для полей сквозь ассоциации). */
    public static List<QueryBuilderMetadataCatalog.Association> chainOf(ConstructorTreeNode node,
                                                                        ConstructorTreeNode owner) {
        Deque<QueryBuilderMetadataCatalog.Association> chain = new ArrayDeque<>();
        ConstructorTreeNode current = node;
        while (current != null && current != owner) {
            if (current.kind() == ConstructorTreeNode.Kind.ASSOCIATION) {
                chain.addFirst(current.association());
            }
            current = current.parent();
        }
        return List.copyOf(chain);
    }

    private String uniqueAlias(String base) {
        String candidate = base;
        int index = 2;
        while (aliasTaken(candidate)) candidate = base + index++;
        return candidate;
    }

    private boolean aliasTaken(String alias) {
        return (entityAlias != null && entityAlias.equals(alias))
                || joins.stream().anyMatch(j -> j.alias().equals(alias));
    }

    private String uniqueResultName(String requested) {
        String base = requested == null || requested.isBlank() ? "field" : sanitize(requested);
        if (!IDENTIFIER.matcher(base).matches()) base = "field";
        String candidate = base;
        int index = 2;
        while (resultNameTaken(candidate)) candidate = base + index++;
        return candidate;
    }

    private boolean resultNameTaken(String name) {
        return selections.stream().anyMatch(s -> s.resultName().equals(name))
                || aggregates.stream().anyMatch(a -> a.resultName().equals(name))
                || expressions.stream().anyMatch(e -> e.resultName().equals(name));
    }

    private static String lowerCamel(String name) {
        if (name == null || name.isBlank()) return "t";
        return sanitize(name.substring(0, 1).toLowerCase(Locale.ROOT) + name.substring(1));
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9_]", "_");
    }

    public static String firstSegment(String path) {
        int dot = path.indexOf('.');
        return dot < 0 ? path : path.substring(0, dot);
    }

    private static String lastSegment(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }

    private static String renamePrefix(String path, String oldAlias, String newAlias) {
        return newAlias + path.substring(oldAlias.length());
    }

    private static String message(RuntimeException error) {
        String text = error.getMessage();
        return text == null || text.isBlank() ? "Ошибка построения запроса" : text;
    }
}
