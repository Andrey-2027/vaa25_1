package org.ipro.reportstudio.query;

import org.ipro.filtergrid.projection.ProjectionFilterCompiler;
import org.ipro.reportstudio.data.QueryField;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Компилятор безопасной модели визуального запроса. */
public final class VisualQueryCompiler {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    /** Маркер в bindings: имя параметра занесено, значение придёт на runtime. */
    public static final String PENDING_PARAM = "__PENDING__";
    /** Allow-list функций: строки, числа, NULL-обработка, безопасные функции дат. */
    private static final Set<String> ALLOWED_FUNCTIONS = Set.of(
            "LOWER", "UPPER", "LENGTH", "TRIM", "CONCAT", "SUBSTRING",
            "ABS", "ROUND", "MOD",
            "COALESCE", "NULLIF",
            "CURRENT_DATE", "CURRENT_TIMESTAMP",
            "YEAR", "MONTH", "DAY");

    /** Имена разрешённых функций — для обратного парсера текста (единый источник истины). */
    public static Set<String> allowedFunctionNames() {
        return ALLOWED_FUNCTIONS;
    }
    private VisualQueryCompiler() { }
    public static ReportQueryAssembler compile(VisualQueryDefinition definition) { return compile(definition, null); }

    /** Компилирует пакет CTE в одном общем контексте параметров. */
    public static ReportQueryAssembler compile(VisualQueryPackage queryPackage,
                                               QueryBuilderMetadataCatalog catalog) {
        if (queryPackage == null) throw new IllegalArgumentException("Пакет запроса обязателен");
        var parameterContext = new ProjectionFilterCompiler.ParameterContext();
        var bindings = new java.util.LinkedHashMap<String, Object>();
        var renderedCtes = new java.util.ArrayList<String>();
        VisualQueryPackage.VirtualCatalog virtualCatalog = VisualQueryPackage.VirtualCatalog.empty();
        Map<String, QueryBuilderMetadataCatalog.Entity> virtualEntities = new HashMap<>();
        for (VisualQueryPackage.Cte cte : queryPackage.ctes()) {
            VisualQueryDefinition definition = cte.definition();
            if (cte.source() instanceof VisualQueryPackage.CteSource source) {
                definition = withEntityName(definition, source.cteName());
            } else if (cte.source() instanceof VisualQueryPackage.EntitySource source
                    && !source.name().equals(definition.entityName())) {
                definition = withEntityName(definition, source.name());
            }
            ReportQueryAssembler compiled = compile(definition, catalog, parameterContext, virtualEntities, virtualCatalog);
            renderedCtes.add(cte.name() + " as (" + compiled.jpql() + ")");
            if (catalog != null) {
                virtualCatalog = virtualCatalog.add(cte.name(), definition, catalog);
                virtualEntities.put(cte.name(), virtualCatalog.entity(cte.name()));
            }
            bindings.putAll(compiled.bindings());
        }
        ReportQueryAssembler main = compile(queryPackage.main(), catalog, parameterContext, virtualEntities, virtualCatalog);
        bindings.putAll(main.bindings());
        String jpql = main.jpql();
        if (!renderedCtes.isEmpty()) jpql = "with " + String.join(", ", renderedCtes) + " " + jpql;
        return new ReportQueryAssembler(jpql, bindings, main.fields(), main.warnings());
    }

    public static ReportQueryAssembler compile(VisualQueryDefinition definition, QueryBuilderMetadataCatalog catalog) {
        return compile(definition, catalog, new ProjectionFilterCompiler.ParameterContext(), Map.of(), VisualQueryPackage.VirtualCatalog.empty());
    }

    private static ReportQueryAssembler compile(VisualQueryDefinition definition,
                                                QueryBuilderMetadataCatalog catalog,
                                                ProjectionFilterCompiler.ParameterContext parameterContext) {
        return compile(definition, catalog, parameterContext, Map.of(), VisualQueryPackage.VirtualCatalog.empty());
    }

    private static ReportQueryAssembler compile(VisualQueryDefinition definition,
                                                QueryBuilderMetadataCatalog catalog,
                                                ProjectionFilterCompiler.ParameterContext parameterContext,
                                                Map<String, QueryBuilderMetadataCatalog.Entity> virtualEntities,
                                                VisualQueryPackage.VirtualCatalog virtualCatalog) {
        if (definition == null) throw new IllegalArgumentException("Определение запроса обязательно");
        validateIdentifier(definition.entityName(), "сущности"); validateIdentifier(definition.entityAlias(), "alias сущности");
        QueryBuilderMetadataCatalog.Entity root = virtualEntities.get(definition.entityName());
        if (root == null && catalog != null) root = catalog.root(definition.entityName());
        Map<String, QueryBuilderMetadataCatalog.Entity> aliases = new HashMap<>(); aliases.put(definition.entityAlias(), root);
        Set<String> declared = new HashSet<>(); declared.add(definition.entityAlias()); Set<String> paths = new HashSet<>(); StringBuilder joins = new StringBuilder();
        for (VisualQueryDefinition.Join join : definition.joins()) {
            validateIdentifier(join.alias(), "alias JOIN"); if (!declared.add(join.alias())) throw new IllegalArgumentException("Alias уже используется: " + join.alias());
            if (join.independent() && join.on() != null) {
                if (!paths.add(join.sourcePath())) throw new IllegalArgumentException("Повторный JOIN: " + join.sourcePath());
                String targetEntity = join.sourcePath(); if (targetEntity.startsWith(definition.entityAlias() + ".")) targetEntity = targetEntity.substring(definition.entityAlias().length() + 1);
                validateIdentifier(targetEntity, "целевой сущности JOIN"); aliases.put(join.alias(), independentJoinTarget(targetEntity, virtualEntities, catalog));
                String on = compileOn(join.on(), aliases, catalog); if (on.isBlank()) throw new IllegalArgumentException("Для независимого JOIN требуется условие ON");
                joins.append(' ').append(join.kind() == VisualQueryDefinition.JoinKind.LEFT ? "left join " : "join ").append(targetEntity).append(' ').append(join.alias()).append(" on ").append(on); continue;
            }
            if (join.independent()) throw new IllegalArgumentException("Для независимого JOIN «" + join.alias()
                    + "» требуется условие ON — задайте его на вкладке «Связи»");
            String parent = join.parentAlias(); if (parent == null || parent.isBlank()) parent = definition.entityAlias(); validateIdentifier(parent, "родительского alias JOIN"); if (!aliases.containsKey(parent)) throw new IllegalArgumentException("Неизвестный родительский alias: " + parent);
            validatePath(join.sourcePath()); String association = join.sourcePath(); if (association.startsWith(parent + ".")) association = association.substring(parent.length() + 1); if (association.contains(".")) throw new IllegalArgumentException("Путь JOIN должен быть одной связью: " + join.sourcePath());
            String associationName = association; String path = parent + "." + associationName; if (!paths.add(path)) throw new IllegalArgumentException("Повторный JOIN: " + path);
            QueryBuilderMetadataCatalog.Entity source = aliases.get(parent); QueryBuilderMetadataCatalog.Association relation = source == null ? null : source.associations().stream().filter(a -> a.name().equals(associationName)).findFirst().orElse(null);
            if (catalog != null && relation == null) throw new IllegalArgumentException("Связь не разрешена: " + path); if (catalog != null) aliases.put(join.alias(), findEntity(catalog, relation.targetType())); else aliases.put(join.alias(), null);
            joins.append(' ').append(join.kind() == VisualQueryDefinition.JoinKind.LEFT ? "left join " : "join ").append(parent).append('.').append(associationName).append(' ').append(join.alias());
        }
        String select = definition.selectFields().stream().map(field -> { validatePath(field.path()); validateIdentifier(field.resultName(), "alias SELECT"); return resolveExpression(field.path(), definition.entityAlias(), aliases, catalog) + " as " + field.resultName(); }).collect(Collectors.joining(", "));
        String aggregateSelect = definition.aggregates().stream().map(aggregate -> aggregateExpression(aggregate, aliases, definition.entityAlias(), catalog)).collect(Collectors.joining(", "));
        String expressionSelect = definition.expressions().stream().map(expression -> expressionExpression(expression, aliases, definition.entityAlias(), catalog, definition.parameters())).collect(Collectors.joining(", "));
        if (!aggregateSelect.isBlank()) select = select.isBlank() ? aggregateSelect : select + ", " + aggregateSelect;
        if (!expressionSelect.isBlank()) select = select.isBlank() ? expressionSelect : select + ", " + expressionSelect;
        String jpql = "select " + select + " from " + definition.entityName() + " " + definition.entityAlias() + joins;
        Map<String, Object> whereBindings = new HashMap<>();
        if (definition.where() != null) {
            var resolver = catalog == null
                    ? new DefinitionFilterResolver(definition)
                    : new VisualQueryFilterResolver(definition, catalog, virtualCatalog);
            var compiledWhere = new ProjectionFilterCompiler(resolver, parameterContext,
                    subqueryRenderer(definition, catalog, parameterContext, virtualEntities, virtualCatalog))
                    .compile(definition.where());
            if (compiledWhere.predicate() != null) jpql += " where " + compiledWhere.predicate();
            whereBindings.putAll(compiledWhere.bindings());
        }
        if (!definition.groupBy().isEmpty()) jpql += " group by " + definition.groupBy().stream().map(path -> resolveExpression(path, definition.entityAlias(), aliases, catalog)).collect(Collectors.joining(", "));
        Map<String, Object> havingBindings = new HashMap<>();
        if (definition.having() instanceof VisualQueryDefinition.Having having) jpql += " having " + havingExpression(having, definition.aggregates(), havingBindings);
        if (!definition.orders().isEmpty()) {
            jpql += " order by " + definition.orders().stream().map(order -> resolveExpression(order.path(), definition.entityAlias(), aliases, catalog) + " " + order.direction().name().toLowerCase()).collect(Collectors.joining(", "));
        }
        List<QueryField> fields = definition.selectFields().stream().map(field -> { String expression = resolveExpression(field.path(), definition.entityAlias(), aliases, catalog); QueryBuilderMetadataCatalog.Field metadata = fieldMetadata(field.path(), definition.entityAlias(), aliases); Class<?> type = metadata == null ? Object.class : metadata.javaType(); String caption = metadata == null ? field.resultName() : metadata.caption(); return new QueryField(field.resultName(), expression, type, caption, true, false, QueryField.isNumber(type)); }).toList();
        if (virtualEntities != null && !virtualEntities.isEmpty()) {
            List<QueryBuilderMetadataCatalog.Field> virtualFields = new java.util.ArrayList<>();
            definition.selectFields().forEach(field -> virtualFields.add(new QueryBuilderMetadataCatalog.Field(field.resultName(), field.resultName(),
                    fieldMetadata(field.path(), definition.entityAlias(), aliases) == null ? Object.class : fieldMetadata(field.path(), definition.entityAlias(), aliases).javaType(), false)));
            definition.aggregates().forEach(field -> virtualFields.add(new QueryBuilderMetadataCatalog.Field(field.resultName(), field.resultName(),
                    aggregateResultType(field, aliases, definition.entityAlias()), false)));
            definition.expressions().forEach(field -> virtualFields.add(new QueryBuilderMetadataCatalog.Field(field.resultName(), field.resultName(),
                    expressionResultType(field.expression(), aliases, definition.entityAlias()), false)));
            virtualEntities.putIfAbsent(definition.entityName(), new QueryBuilderMetadataCatalog.Entity(definition.entityName(), Object.class, virtualFields, List.of(), definition.entityName()));
        }
        havingBindings.forEach((k, v) -> { if (v != null) whereBindings.put(k, v); });
        return new ReportQueryAssembler(jpql, whereBindings, fields, List.of());
    }
    /**
     * Рендерер подзапросов WHERE: каждое внутреннее определение компилируется один раз
n     * (кэш на вызов) тем же ParameterContext — нумерация :visualFilter_N сквозная.
     */
    private static ProjectionFilterCompiler.SubqueryRenderer subqueryRenderer(VisualQueryDefinition definition,
                                                                                QueryBuilderMetadataCatalog catalog,
                                                                                ProjectionFilterCompiler.ParameterContext parameterContext,
                                                                                Map<String, QueryBuilderMetadataCatalog.Entity> virtualEntities,
                                                                                VisualQueryPackage.VirtualCatalog virtualCatalog) {
        if (definition.subqueries().isEmpty()) return null;
        Map<String, ProjectionFilterCompiler.CompiledSubquery> cache = new HashMap<>();
        return name -> cache.computeIfAbsent(name, key -> {
            var subquery = definition.subqueries().stream().filter(candidate -> candidate.name().equals(key)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Подзапрос не найден: " + key));
            ReportQueryAssembler inner = compile(subquery.definition(), catalog, parameterContext, virtualEntities, virtualCatalog);
            return new ProjectionFilterCompiler.CompiledSubquery("(" + inner.jpql() + ")", inner.bindings());
        });
    }

    private static VisualQueryDefinition withEntityName(VisualQueryDefinition source, String entityName) {
        return new VisualQueryDefinition(source.version(), entityName, source.entityAlias(), source.selectFields(),
                source.joins(), source.groupBy(), source.aggregates(), source.expressions(), source.having(),
                source.where(), source.parameters(), source.orders(), source.subqueries());
    }

    private static Class<?> aggregateResultType(VisualQueryDefinition.Aggregate aggregate,
                                                 Map<String, QueryBuilderMetadataCatalog.Entity> aliases,
                                                 String rootAlias) {
        String function = aggregate.function().toUpperCase();
        if (function.equals("COUNT") || function.equals("COUNT_ROWS")) return Long.class;
        QueryBuilderMetadataCatalog.Field source = fieldMetadata(aggregate.path(), rootAlias, aliases);
        if (function.equals("MIN") || function.equals("MAX")) {
            return source == null ? Object.class : source.javaType();
        }
        if (function.equals("AVG")) return Double.class;
        if (function.equals("SUM")) return source == null ? Number.class : source.javaType();
        return Object.class;
    }

    private static Class<?> expressionResultType(VisualQueryExpression expression,
                                                  Map<String, QueryBuilderMetadataCatalog.Entity> aliases,
                                                  String rootAlias) {
        if (expression instanceof VisualQueryExpression.Literal literal) {
            return literal.value() == null ? Object.class : literal.value().getClass();
        }
        if (expression instanceof VisualQueryExpression.FieldRef field) {
            QueryBuilderMetadataCatalog.Field metadata = fieldMetadata(field.path(), rootAlias, aliases);
            return metadata == null ? Object.class : metadata.javaType();
        }
        if (expression instanceof VisualQueryExpression.Binary) return Number.class;
        if (expression instanceof VisualQueryExpression.Case caseExpression) {
            if (!caseExpression.branches().isEmpty()) {
                return expressionResultType(caseExpression.branches().get(0).result(), aliases, rootAlias);
            }
            return Object.class;
        }
        if (expression instanceof VisualQueryExpression.FunctionCall function) {
            return switch (function.name().toUpperCase()) {
                case "LOWER", "UPPER", "TRIM", "SUBSTRING", "CONCAT" -> String.class;
                case "LENGTH", "YEAR", "MONTH", "DAY", "ABS", "ROUND", "MOD" -> Number.class;
                default -> Object.class;
            };
        }
        return Object.class;
    }

    private static final class DefinitionFilterResolver implements org.ipro.filtergrid.filter.FilterFieldResolver {
        private final VisualQueryDefinition definition;

        private DefinitionFilterResolver(VisualQueryDefinition definition) {
            this.definition = definition;
        }

        @Override
        public org.ipro.filtergrid.filter.FilterFieldResolver.ResolvedFilterField resolve(String path) {
            return new org.ipro.filtergrid.filter.FilterFieldResolver.ResolvedFilterField(
                    path, path, String.class, org.ipro.filtergrid.filter.FilterDataType.TEXT, true);
        }

        @Override
        public List<org.ipro.filtergrid.filter.FilterFieldResolver.ResolvedFilterField> fields() {
            return definition.selectFields().stream()
                    .map(field -> resolve(field.path()))
                    .toList();
        }

        @Override
        public List<?> valueOptions(org.ipro.filtergrid.filter.FilterFieldResolver.ResolvedFilterField field) {
            return List.of();
        }
    }

    private static String aggregateExpression(VisualQueryDefinition.Aggregate aggregate, Map<String, QueryBuilderMetadataCatalog.Entity> aliases, String rootAlias, QueryBuilderMetadataCatalog catalog) {
        String function = aggregate.function().toUpperCase();
        if (!Set.of("SUM", "AVG", "MIN", "MAX", "COUNT", "COUNT_ROWS").contains(function)) throw new IllegalArgumentException("Неподдерживаемая функция: " + function);
        validateIdentifier(aggregate.resultName(), "alias агрегата");
        if ("COUNT_ROWS".equals(function)) return "count(" + rootAlias + ".id) as " + aggregate.resultName();
        QueryBuilderMetadataCatalog.Field field = fieldMetadata(aggregate.path(), rootAlias, aliases);
        if (catalog != null && field == null) throw new IllegalArgumentException("Поле агрегата не разрешено: " + aggregate.path());
        // SUM/AVG требуют числа; COUNT/COUNT_ROWS/MIN/MAX допустимы для любых полей (даты/строки — как в 1С).
        if (catalog != null && field != null && ("SUM".equals(function) || "AVG".equals(function))
                && !QueryField.isNumber(field.javaType())) {
            throw new IllegalArgumentException("Агрегат " + function + " недоступен для поля: " + aggregate.path());
        }
        return function.toLowerCase() + "(" + resolveExpression(aggregate.path(), rootAlias, aliases, catalog) + ") as " + aggregate.resultName();
    }
    private static String expressionExpression(VisualQueryDefinition.Expression expression, Map<String, QueryBuilderMetadataCatalog.Entity> aliases, String rootAlias, QueryBuilderMetadataCatalog catalog, List<VisualQueryDefinition.Parameter> definitionParameters) {
        validateIdentifier(expression.resultName(), "alias выражения");
        // Голый литерал разрешён как константная колонка («'1' as val1»): значение
        // типизировано в Java, строки экранируются — инъекций нет.
        return renderExpression(expression.expression(), aliases, rootAlias, catalog, definitionParameters) + " as " + expression.resultName();
    }
    private static String renderExpression(VisualQueryExpression expression, Map<String, QueryBuilderMetadataCatalog.Entity> aliases, String rootAlias, QueryBuilderMetadataCatalog catalog, List<VisualQueryDefinition.Parameter> definitionParameters) {
        if (expression instanceof VisualQueryExpression.FieldRef field) { validatePath(field.path()); return resolveExpression(field.path(), rootAlias, aliases, catalog); }
        if (expression instanceof VisualQueryExpression.Literal literal) {
            // Безопасная константа: значение литерала всегда типизировано в Java-модели,
            // а не сырой текст; строки экранируются, числа форматируются.
            if (literal.value() instanceof String s) return "'" + s.replace("'", "''") + "'";
            if (literal.value() instanceof Number n) return formatNumber(n.doubleValue());
            if (literal.value() instanceof Boolean b) return b.toString();
            if (literal.value() == null) return "null";
            throw new IllegalArgumentException("Неподдерживаемый тип литерала: " + literal.value().getClass());
        }
        if (expression instanceof VisualQueryExpression.ParameterRef parameter) {
            if (definitionParameters == null || definitionParameters.stream().noneMatch(p -> p.name().equals(parameter.name()))) {
                throw new IllegalArgumentException("Параметр не объявлен: " + parameter.name());
            }
            return ":" + parameter.name();
        }
        if (expression instanceof VisualQueryExpression.Binary binary) return "(" + renderExpression(binary.left(), aliases, rootAlias, catalog, definitionParameters) + " " + binary.operator().symbol() + " " + renderExpression(binary.right(), aliases, rootAlias, catalog, definitionParameters) + ")";
        if (expression instanceof VisualQueryExpression.Case caseExpr) return renderCase(caseExpr, aliases, rootAlias, catalog, definitionParameters);
        VisualQueryExpression.FunctionCall function = (VisualQueryExpression.FunctionCall) expression;
        validateIdentifier(function.name(), "имени функции");
        if (!ALLOWED_FUNCTIONS.contains(function.name().toUpperCase())) throw new IllegalArgumentException("Функция не разрешена: " + function.name());
        if (function.arguments().isEmpty()) throw new IllegalArgumentException("Функция должна иметь аргументы");
        if ("NULLIF".equalsIgnoreCase(function.name()) && function.arguments().size() != 2) throw new IllegalArgumentException("NULLIF требует ровно два аргумента");
        return function.name().toLowerCase() + "(" + function.arguments().stream().map(arg -> renderExpression(arg, aliases, rootAlias, catalog, definitionParameters)).collect(Collectors.joining(", ")) + ")";
    }
    /** Безопасный CASE WHEN: только field-vs-number / BETWEEN условия и типизированные результаты. */
    private static String renderCase(VisualQueryExpression.Case caseExpr, Map<String, QueryBuilderMetadataCatalog.Entity> aliases, String rootAlias, QueryBuilderMetadataCatalog catalog, List<VisualQueryDefinition.Parameter> definitionParameters) {
        StringBuilder sb = new StringBuilder("case");
        for (CaseBranch branch : caseExpr.branches()) {
            sb.append(" when ").append(renderCaseCondition(branch.condition(), aliases, rootAlias, catalog));
            sb.append(" then ").append(renderExpression(branch.result(), aliases, rootAlias, catalog, definitionParameters));
        }
        if (caseExpr.elseResult() != null) {
            sb.append(" else ").append(renderExpression(caseExpr.elseResult(), aliases, rootAlias, catalog, definitionParameters));
        }
        return sb.append(" end").toString();
    }

    private static String renderCaseCondition(CaseCondition cond, Map<String, QueryBuilderMetadataCatalog.Entity> aliases, String rootAlias, QueryBuilderMetadataCatalog catalog) {
        if (cond instanceof CaseCondition.Cmp cmp) {
            String left = resolveExpression(cmp.leftPath(), rootAlias, aliases, catalog);
            return left + " " + cmp.op().symbol() + " " + formatNumber(cmp.value());
        }
        CaseCondition.Between bt = (CaseCondition.Between) cond;
        String left = resolveExpression(bt.leftPath(), rootAlias, aliases, catalog);
        return left + " between " + formatNumber(bt.min()) + " and " + formatNumber(bt.max());
    }

    private static String formatNumber(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    private static String havingExpression(VisualQueryDefinition.Having having, List<VisualQueryDefinition.Aggregate> aggregates, Map<String, Object> havingBindings) {
        Set<String> aggAliases = aggregates.stream().map(VisualQueryDefinition.Aggregate::resultName).collect(Collectors.toSet());
        String op = having.operator() == JoinLogicalOperator.OR ? " OR " : " AND ";
        return "(" + having.conditions().stream().map(c -> {
            if (!aggAliases.contains(c.aggregateAlias())) {
                throw new IllegalArgumentException("HAVING ссылается на неизвестный агрегат: " + c.aggregateAlias());
            }
            String lhs = c.aggregateAlias();
            String rhs = havingValueExpression(c.value(), havingBindings);
            return lhs + " " + c.operator().symbol() + " " + rhs;
        }).collect(Collectors.joining(op)) + ")";
    }

    /** Значение HAVING: число — как есть; :параметр — именованный биндинг; агрегат — его alias. */
    private static String havingValueExpression(Object rawValue, Map<String, Object> havingBindings) {
        if (rawValue instanceof VisualQueryDefinition.HavingNumber n) {
            return Double.toString(n.value());
        }
        if (rawValue instanceof VisualQueryDefinition.HavingParamRef p) {
            // Имя заносится в bindings как маркер — guard белеет-листит его, значение
            // подставит VisualQueryParameterRuntime на runtime (или упадём раньше с
            // понятной ошибкой, если значение так и не пришло).
            if (!havingBindings.containsKey(p.name())) havingBindings.put(p.name(), PENDING_PARAM);
            return ":" + p.name();
        }
        VisualQueryDefinition.HavingAggregateRef a = (VisualQueryDefinition.HavingAggregateRef) rawValue;
        return a.alias();
    }
    /** Цель независимого JOIN: ранее объявленный CTE (виртуальная сущность) или обычная сущность каталога. */
    private static QueryBuilderMetadataCatalog.Entity independentJoinTarget(String targetEntity,
                                                                            Map<String, QueryBuilderMetadataCatalog.Entity> virtualEntities,
                                                                            QueryBuilderMetadataCatalog catalog) {
        QueryBuilderMetadataCatalog.Entity virtual = virtualEntities.get(targetEntity);
        if (virtual != null) return virtual;
        if (catalog == null) return null;
        try {
            return catalog.root(targetEntity);
        } catch (RuntimeException unknown) {
            return null;
        }
    }
    private static String compileOn(JoinCondition condition, Map<String, QueryBuilderMetadataCatalog.Entity> aliases, QueryBuilderMetadataCatalog catalog) { if (condition instanceof JoinCondition.Group group) { String op = group.operator() == JoinLogicalOperator.OR ? " OR " : " AND "; return "(" + group.children().stream().map(c -> compileOn(c, aliases, catalog)).collect(Collectors.joining(op)) + ")"; } JoinCondition.Predicate p = (JoinCondition.Predicate) condition; QueryBuilderMetadataCatalog.Field l = validateFieldReference(p.leftPath(), aliases, catalog), r = validateFieldReference(p.rightPath(), aliases, catalog); if (catalog != null && !sameComparableType(l.javaType(), r.javaType())) throw new IllegalArgumentException("Несовместимые типы полей в ON"); return p.leftPath() + (p.operator() == JoinCondition.Operator.EQ ? " = " : " <> ") + p.rightPath(); }
    private static QueryBuilderMetadataCatalog.Field validateFieldReference(String value, Map<String, QueryBuilderMetadataCatalog.Entity> aliases, QueryBuilderMetadataCatalog catalog) { if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_]*\\.[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Недопустимая ссылка поля в ON: " + value); String alias = value.substring(0, value.indexOf('.')); String name = value.substring(value.indexOf('.') + 1); if ("id".equals(name)) return new QueryBuilderMetadataCatalog.Field("id", "Идентификатор записи", Long.class, true); QueryBuilderMetadataCatalog.Entity entity = aliases.get(alias); if (!aliases.containsKey(alias) || catalog != null && !hasField(entity, name)) throw new IllegalArgumentException("Недопустимая ссылка поля в ON: " + value); return catalog == null ? new QueryBuilderMetadataCatalog.Field(name, name, Object.class, false) : entity.fields().stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow(); }
    private static boolean sameComparableType(Class<?> a, Class<?> b) { return a.equals(b) || Number.class.isAssignableFrom(a) && Number.class.isAssignableFrom(b); }
    private static String resolveExpression(String path, String rootAlias, Map<String, QueryBuilderMetadataCatalog.Entity> aliases, QueryBuilderMetadataCatalog catalog) { String[] s = path.split("\\."); if (s.length == 1) { if (catalog != null && !hasSelectable(aliases.get(rootAlias), s[0])) throw new IllegalArgumentException("Поле не разрешено: " + path); return rootAlias + "." + s[0]; } if (!aliases.containsKey(s[0])) throw new IllegalArgumentException("Неизвестный путь: " + path); if (catalog != null && !hasSelectablePath(aliases, s, catalog)) throw new IllegalArgumentException("Поле не разрешено: " + path); return path; }
    /** Путь «alias.assoc.assoc.field» — JPQL-traversal: промежуточные сегменты — ассоциации, последний — селектируемое поле. */
    private static boolean hasSelectablePath(Map<String, QueryBuilderMetadataCatalog.Entity> aliases, String[] segments, QueryBuilderMetadataCatalog catalog) {
        QueryBuilderMetadataCatalog.Entity entity = aliases.get(segments[0]);
        if (entity == null) return false;
        for (int i = 1; i < segments.length; i++) {
            if (i == segments.length - 1) return hasSelectable(entity, segments[i]);
            int idx = i;
            var association = entity.associations().stream().filter(a -> a.name().equals(segments[idx])).findFirst().orElse(null);
            if (association == null || association.targetType() == null) return false;
            entity = catalog.roots().stream().filter(e -> e.javaType().equals(association.targetType())).findFirst().orElse(null);
            if (entity == null) return false;
        }
        return true;
    }
    private static QueryBuilderMetadataCatalog.Field fieldMetadata(String path, String rootAlias, Map<String, QueryBuilderMetadataCatalog.Entity> aliases) { String[] s = path.split("\\."); QueryBuilderMetadataCatalog.Entity e = aliases.get(s.length == 1 ? rootAlias : s[0]); if (e == null) return null; String name = s[s.length - 1]; if ("id".equals(name)) return new QueryBuilderMetadataCatalog.Field("id", "Идентификатор записи", Long.class, true); if (s.length > 2) return null; return e.fields().stream().filter(f -> f.name().equals(name)).findFirst()
            .orElseGet(() -> e.associations().stream().filter(a -> a.name().equals(name)).findFirst()
                    .map(a -> new QueryBuilderMetadataCatalog.Field(a.name(), a.caption(),
                            a.targetType() == null ? Object.class : a.targetType(), false))
                    .orElse(null)); }
    /** Селектируемое поле: обычное поле ИЛИ сущностная ссылка (ассоциация) — как в 1С,
     *  плюс системный идентификатор записи (id — PK BaseEntity, для привязки строк отчёта). */
    private static boolean hasSelectable(QueryBuilderMetadataCatalog.Entity e, String n) {
        if (e == null) return false;
        return e.fields().stream().anyMatch(f -> f.name().equals(n))
                || e.associations().stream().anyMatch(a -> a.name().equals(n))
                || "id".equals(n);
    }
    private static boolean hasField(QueryBuilderMetadataCatalog.Entity e, String n) { return e != null && e.fields().stream().anyMatch(f -> f.name().equals(n)); }
    private static QueryBuilderMetadataCatalog.Entity findEntity(QueryBuilderMetadataCatalog c, Class<?> t) { return c.roots().stream().filter(e -> e.javaType().equals(t)).findFirst().orElseThrow(() -> new IllegalArgumentException("Целевая сущность JOIN не разрешена")); }
    private static void validatePath(String p) { if (p == null || p.isBlank()) throw new IllegalArgumentException("Путь обязателен"); for (String s : p.split("\\.")) validateIdentifier(s, "пути"); }
    private static void validateIdentifier(String v, String k) { if (v == null || !IDENTIFIER.matcher(v).matches()) throw new IllegalArgumentException("Недопустимое имя " + k + ": " + v); }
}
