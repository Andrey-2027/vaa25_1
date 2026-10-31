package org.ipro.query;

import org.ipro.register.*;
import org.ipro.query.register.*;
import java.util.*;
import java.time.*;
import java.math.BigDecimal;

/** Application query syntax. The ORM receives only the lowered, guarded HQL. */
public final class ReportQueryLanguage {
    private final QueryBuilderMetadataCatalog catalog;
    private final RegisterSliceReportTemplates recipes;
    public ReportQueryLanguage(QueryBuilderMetadataCatalog catalog, RegisterSliceReportTemplates recipes) {
        this.catalog = catalog; this.recipes = recipes;
    }
    public record Parsed(VisualQueryPackage pack, String json, ReportQueryAssembler compiled,
                         Map<String, Class<?>> entityParameters) { }
    private record Token(String text, int start, int end, boolean quoted) { }
    public static boolean containsExtensions(String text) {
        return tokens(text == null ? "" : text).stream().anyMatch(token -> !token.quoted()
            && (token.text().equalsIgnoreCase("СрезПоследних") || token.text().equalsIgnoreCase("Представление")));
    }
    public Parsed parse(String text) {
        try { return parseComplete(text); }
        catch (RuntimeException failure) {
            if (failure.getMessage() != null && failure.getMessage().startsWith("Строка ")) throw failure;
            String marker = failure.getMessage() != null && failure.getMessage().contains("Представление") ? "Представление" : "СрезПоследних";
            int offset = text == null ? 0 : Math.max(0, text.indexOf(marker));
            throw located(text == null ? "" : text, offset, failure.getMessage());
        }
    }
    private Parsed parseComplete(String text) {
        var tokens = tokens(text); validateResultNames(tokens, text); var sources = new ArrayList<RegisterSliceReportTemplates.ConstructorSlice>();
        var occupied = new LinkedHashSet<String>(); tokens.stream().filter(token -> !token.quoted()).forEach(token -> occupied.add(token.text()));
        var replaced = new StringBuilder(); int copied = 0;
        for (int index = 0; index < tokens.size(); index++) {
            var token = tokens.get(index);
            if (token.quoted() || !token.text().equalsIgnoreCase("СрезПоследних")) continue;
            try {
                if (index == 0 || !(tokens.get(index - 1).text().equalsIgnoreCase("from") || tokens.get(index - 1).text().equalsIgnoreCase("join")))
                    throw new IllegalArgumentException("СрезПоследних допускается только как источник FROM или JOIN");
                if (index + 1 >= tokens.size() || !tokens.get(index + 1).text().equals("(")) throw new IllegalArgumentException("Ожидается (");
                int end = closing(tokens, index + 1);
                var args = split(text.substring(tokens.get(index + 1).end(), tokens.get(end).start()), ",");
                if (args.size() < 2 || args.size() > 5) throw new IllegalArgumentException("Укажите регистр, параметр даты и необязательные отборы");
                var entity = catalog.root(args.get(0).strip()); String date = args.get(1).strip();
                if (!date.matches(":[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Дата среза задаётся именованным параметром");
                var before = new ArrayList<SliceSpec.Condition>(); var after = new ArrayList<SliceSpec.Condition>();
                var params = new ArrayList<SliceParameterFilter>(); var recorders = new ArrayList<RecorderRef>();
                var options = new HashSet<String>(); boolean dimensionSeen = false;
                for (int arg = 2; arg < args.size(); arg++) {
                    String value = args.get(arg).strip(); var parts = tokens(value);
                    String key = parts.isEmpty() ? "" : parts.getFirst().text();
                    if (key.equalsIgnoreCase("послеСреза") || key.equalsIgnoreCase("регистраторы")) {
                        if (!options.add(key.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Повторный аргумент: " + key);
                        if (parts.size() < 3 || !parts.get(1).text().equals("=")) throw new IllegalArgumentException("Ожидается " + key + " = (...)");
                        String body = unwrap(value.substring(parts.get(1).end()).strip());
                        if (key.equalsIgnoreCase("послеСреза")) conditions(body, entity.javaType(), true, after, params);
                        else for (String tuple : split(body, ",")) {
                            var identity = split(unwrap(tuple.strip()), ",");
                            if (identity.size() != 2) throw new IllegalArgumentException("Регистратор задаётся парой ('кодТипа', id)");
                            recorders.add(new RecorderRef(unquote(identity.getFirst()), Long.valueOf(identity.get(1).strip())));
                        }
                    } else {
                        if (dimensionSeen) throw new IllegalArgumentException("Отбор измерений задаётся одним аргументом");
                        dimensionSeen = true; conditions(value, entity.javaType(), false, before, params);
                    }
                }
                var intent = recorders.isEmpty() ? SliceSpec.RecorderReadIntent.allRecorders() : SliceSpec.RecorderReadIntent.selectedRecorders(recorders);
                var spec = new SliceSpec(entity.javaType(), SliceSpec.Mode.LATEST, LocalDateTime.MIN, before, after, intent);
                var source = recipes.constructorSlice(spec, date.substring(1), occupied, params);
                sources.add(source); source.stages().forEach(stage -> occupied.add(stage.name()));
                replaced.append(text, copied, token.start()).append(source.name()); copied = tokens.get(end).end(); index = end;
            } catch (RuntimeException failure) { throw located(text, token.start(), failure.getMessage()); }
        }
        replaced.append(text, copied, text.length());
        var virtual = sources.stream().map(source -> new VisualQueryPackage.VirtualEntity(source.name(), source.entity().fields())).toList();
        var parsed = new VisualQueryTextParser(catalog).withSources(virtual).withNamedConditions().parsePackage(replaced.toString());
        if (parsed.queryPackage() == null || !parsed.warnings().isEmpty())
            throw located(text, 0, parsed.warnings().isEmpty() ? "Запрос не разобран" : String.join("; ", parsed.warnings()));
        var definitions = new ArrayList<VisualQueryPackage.Cte>(); sources.forEach(source -> definitions.addAll(source.stages()));
        definitions.addAll(parsed.queryPackage().ctes());
        var main = parsed.queryPackage().main(); var parameters = new LinkedHashMap<String, VisualQueryDefinition.Parameter>();
        main.parameters().forEach(parameter -> parameters.put(parameter.name(), parameter));
        for (var stage : parsed.queryPackage().ctes()) for (var parameter : stage.definition().parameters()) {
            var previous = parameters.putIfAbsent(parameter.name(), parameter);
            if (previous != null && !previous.dataType().equals(parameter.dataType()))
                throw new IllegalArgumentException("Несовместимые типы параметра: " + parameter.name());
        }
        var entityParameters = new LinkedHashMap<>(entityParameters(sources));
        registerDeclaredEntities(parsed.queryPackage(), entityParameters);
        for (var source : sources) {
            for (var parameter : source.stages().getFirst().definition().parameters()) {
                var previous = parameters.get(parameter.name());
                if (previous != null && !previous.dataType().equals("TEXT") && !previous.dataType().equals(parameter.dataType()))
                    throw new IllegalArgumentException("Несовместимые типы параметра: " + parameter.name());
                parameters.put(parameter.name(), parameter);
            }
            for (var filter : source.parameterFilters()) {
                var field = recipes.sliceFilters(source.spec().registerType(), filter.afterSlice()).stream()
                    .filter(value -> value.name().equals(filter.field())).findFirst().orElseThrow();
                Class<?> type = field.javaType();
                String dataType = field.referenceType() != null ? "ENTITY" : type == LocalDateTime.class ? "DATETIME" : type == LocalDate.class ? "DATE" : type.getName();
                var previous = parameters.get(filter.parameter());
                if (previous != null && !previous.dataType().equals("TEXT") && !previous.dataType().equals(dataType)
                        && !(dataType.equals("ENTITY") && entityParameters.containsKey(filter.parameter())
                            && previous.dataType().equals(entityParameters.get(filter.parameter()).getName())))
                    throw new IllegalArgumentException("Несовместимые типы параметра: " + filter.parameter());
                parameters.put(filter.parameter(), new VisualQueryDefinition.Parameter(filter.parameter(), dataType, true, null));
            }
        }
        main = withParameters(main, List.copyOf(parameters.values()));
        var pack = new VisualQueryPackage(definitions, main);
        var compiled = VisualQueryCompiler.compile(pack, catalog);
        String json = recipes.saveConstructorSlices(new VisualQueryPackageJsonCodec().write(pack), sources);
        return new Parsed(pack, json, compiled, entityParameters);
    }
    public Map<String, Class<?>> entityParameters(String json) {
        var result = new LinkedHashMap<>(entityParameters(recipes.constructorSlices(json)));
        registerDeclaredEntities(new VisualQueryPackageJsonCodec().read(json), result); return Map.copyOf(result);
    }
    private void registerDeclaredEntities(VisualQueryPackage pack, Map<String, Class<?>> entities) {
        var types = new LinkedHashMap<String, Class<?>>();
        for (var entity : catalog.roots()) {
            if (entity.javaType() != Object.class) types.put(entity.javaType().getName(), entity.javaType());
            for (var association : entity.associations()) if (association.targetType() != null)
                types.put(association.targetType().getName(), association.targetType());
        }
        var definitions = new ArrayList<VisualQueryDefinition>(); definitions.add(pack.main()); pack.ctes().forEach(stage -> definitions.add(stage.definition()));
        for (var definition : definitions) for (var parameter : definition.parameters()) {
            Class<?> type = types.get(parameter.dataType()); if (type == null) continue;
            Class<?> previous = entities.putIfAbsent(parameter.name(), type);
            if (previous != null && previous != type) throw new IllegalArgumentException("Несовместимый тип ссылочного параметра: " + parameter.name());
        }
    }
    private Map<String, Class<?>> entityParameters(List<RegisterSliceReportTemplates.ConstructorSlice> sources) {
        var result = new LinkedHashMap<String, Class<?>>(); var scalarNames = new HashSet<String>();
        for (var source : sources) for (var filter : source.parameterFilters()) {
            var field = recipes.sliceFilters(source.spec().registerType(), filter.afterSlice()).stream().filter(value -> value.name().equals(filter.field())).findFirst().orElseThrow();
            if (field.referenceType() == null) {
                if (result.containsKey(filter.parameter())) throw new IllegalArgumentException("Несовместимый параметр: " + filter.parameter());
                scalarNames.add(filter.parameter());
            } else {
                var previous = result.putIfAbsent(filter.parameter(), field.referenceType());
                if (scalarNames.contains(filter.parameter()) || previous != null && previous != field.referenceType()) throw new IllegalArgumentException("Несовместимый тип ссылочного параметра: " + filter.parameter());
            }
        }
        return Map.copyOf(result);
    }
    public Optional<String> format(String visualQueryJson) {
        if (visualQueryJson == null) return Optional.empty();
        var pack = new VisualQueryPackageJsonCodec().read(visualQueryJson);
        var sources = recipes.constructorSlices(visualQueryJson);
        if (sources.isEmpty() && pack.main().aggregates().stream().noneMatch(a -> "LISTAGG".equalsIgnoreCase(a.function()))
            && pack.main().expressions().stream().noneMatch(expression -> QueryPresentationCompiler.isPresentation(expression.expression()))) return Optional.empty();
        var compiled = VisualQueryCompiler.compile(pack, catalog); String text = compiled.jpql();
        var owned = new HashSet<String>(); sources.forEach(source -> source.stages().forEach(stage -> owned.add(stage.name())));
        if (text.stripLeading().startsWith("with ")) {
            int main = topLevelWord(text, "select", 4); var declarations = split(text.substring(4, main), ",");
            var keep = declarations.stream().filter(declaration -> !owned.contains(tokens(declaration).getFirst().text())).toList();
            text = (keep.isEmpty() ? "" : "with " + String.join(", ", keep) + " ") + text.substring(main);
        }
        int main = text.stripLeading().startsWith("with ") ? topLevelWord(text, "select", 4) : 0;
        int from = topLevelWord(text, "from", main); var selections = new ArrayList<String>();
        pack.main().selectFields().forEach(field -> selections.add(field.path() + " as " + field.resultName()));
        pack.main().aggregates().forEach(field -> selections.add((field.function().equalsIgnoreCase("LISTAGG")
            ? VisualQueryCompiler.listaggText(field, field.path(), field.orderPath())
            : field.function().equals("COUNT_ROWS") ? "count(" + field.path() + ")"
            : field.function().toLowerCase(Locale.ROOT) + "(" + field.path() + ")") + " as " + field.resultName()));
        pack.main().expressions().forEach(field -> selections.add(VisualQueryExpressionText.render(field.expression()) + " as " + field.resultName()));
        text = text.substring(0, main) + "select " + String.join(", ", selections) + " " + text.substring(from);
        var output = new StringBuilder(); int copied = 0; String previous = "";
        for (var token : tokens(text)) {
            var source = sources.stream().filter(value -> value.name().equals(token.text())).findFirst();
            String value = token.text();
            if (!token.quoted() && source.isPresent() && (previous.equalsIgnoreCase("from") || previous.equalsIgnoreCase("join"))) value = sourceText(source.get());
            else if (!token.quoted() && value.startsWith(":")) {
                Object binding = compiled.bindings().get(value.substring(1));
                if (binding != null && !VisualQueryCompiler.PENDING_PARAM.equals(binding)) value = literal(binding);
            }
            output.append(text, copied, token.start()).append(value); copied = token.end(); previous = token.quoted() ? "" : token.text();
        }
        output.append(text, copied, text.length()); return Optional.of(JpqlFormatter.format(output.toString()));
    }
    public String sourceText(RegisterSliceReportTemplates.ConstructorSlice source) {
        var args = new ArrayList<String>(); args.add(source.registerEntity()); args.add(":" + source.dateParameter());
        String before = conditionsText(source, false); if (!before.isEmpty()) args.add(before);
        String after = conditionsText(source, true); if (!after.isEmpty()) args.add("послеСреза = (" + after + ")");
        if (!source.spec().recorderReadIntent().selectedRecorders().isEmpty()) args.add("регистраторы = (" + source.spec().recorderReadIntent().selectedRecorders().stream()
            .map(reference -> "('" + reference.getTypeCode() + "', " + reference.getId() + ")").collect(java.util.stream.Collectors.joining(", ")) + ")");
        return "СрезПоследних(" + String.join(", ", args) + ")";
    }
    private String conditionsText(RegisterSliceReportTemplates.ConstructorSlice source, boolean after) {
        var conditions = new ArrayList<String>();
        for (var filter : after ? source.spec().afterSlice() : source.spec().dimensions()) conditions.add(filter.field() + " " + operator(filter.operator()) + " " + literal(filter.value()));
        source.parameterFilters().stream().filter(filter -> filter.afterSlice() == after).forEach(filter -> conditions.add(filter.field() + " " + operator(filter.operator()) + " :" + filter.parameter()));
        return String.join(" and ", conditions);
    }
    private void conditions(String text, Class<?> type, boolean after, List<SliceSpec.Condition> values, List<SliceParameterFilter> params) {
        for (String item : split(text, "and")) {
            var parts = tokens(item);
            if (parts.size() < 3) throw new IllegalArgumentException("Ожидается поле, сравнение и значение: " + item);
            String name = parts.getFirst().text(); var field = recipes.sliceFilters(type, after).stream().filter(value -> value.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Поле недоступно в этой фазе: " + name));
            var operator = switch (parts.get(1).text()) { case "=" -> SliceSpec.Operator.EQ; case "<>", "!=" -> SliceSpec.Operator.NE;
                case ">" -> SliceSpec.Operator.GT; case ">=" -> SliceSpec.Operator.GE; case "<" -> SliceSpec.Operator.LT; case "<=" -> SliceSpec.Operator.LE;
                default -> throw new IllegalArgumentException("Неподдерживаемое сравнение"); };
            String value = item.substring(parts.get(1).end()).strip();
            if (value.matches(":[A-Za-z_][A-Za-z0-9_]*")) params.add(new SliceParameterFilter(name, operator, value.substring(1), after));
            else values.add(new SliceSpec.Condition(name, operator, scalar(field.javaType(), value)));
        }
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object scalar(Class<?> type, String raw) {
        String value = unquote(raw);
        if (type == String.class) { if (!raw.strip().startsWith("'")) throw new IllegalArgumentException("Строка должна быть в кавычках"); return value; }
        if (type == Long.class || type == long.class) return Long.valueOf(value);
        if (type == Integer.class || type == int.class) return Integer.valueOf(value);
        if (type == Short.class || type == short.class) return Short.valueOf(value);
        if (type == BigDecimal.class) return new BigDecimal(value);
        if (type == Double.class || type == double.class) return Double.valueOf(value);
        if (type == Float.class || type == float.class) return Float.valueOf(value);
        if (type == Boolean.class || type == boolean.class) { if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) throw new IllegalArgumentException("Ожидается true или false"); return Boolean.valueOf(value); }
        if (type == LocalDate.class) return LocalDate.parse(value);
        if (type == LocalDateTime.class) return LocalDateTime.parse(value.replace(' ', 'T'));
        if (type == UUID.class) return UUID.fromString(value);
        if (type.isEnum()) return Enum.valueOf((Class) type, value);
        throw new IllegalArgumentException("Неподдерживаемый тип значения: " + type.getSimpleName());
    }
    private static String operator(SliceSpec.Operator operator) { return switch (operator) { case EQ -> "="; case NE -> "<>"; case GT -> ">"; case GE -> ">="; case LT -> "<"; case LE -> "<="; }; }
    private static String literal(Object value) { return value instanceof Number || value instanceof Boolean ? value.toString() : "'" + (value instanceof Enum<?> enumeration ? enumeration.name() : value.toString()).replace("'", "''") + "'"; }
    private static String unquote(String raw) { String value = raw.strip(); return value.startsWith("'") && value.endsWith("'") ? value.substring(1, value.length() - 1).replace("''", "'") : value; }
    private static String unwrap(String text) { var tokens = tokens(text); if (tokens.isEmpty() || !tokens.getFirst().text().equals("(") || closing(tokens, 0) != tokens.size() - 1) throw new IllegalArgumentException("Ожидается (...)"); return text.substring(tokens.getFirst().end(), tokens.getLast().start()); }
    private static int closing(List<Token> tokens, int start) { int depth = 0; for (int index = start; index < tokens.size(); index++) if (!tokens.get(index).quoted()) { if (tokens.get(index).text().equals("(")) depth++; if (tokens.get(index).text().equals(")") && --depth == 0) return index; } throw new IllegalArgumentException("Не закрыта скобка"); }
    private static List<String> split(String text, String separator) {
        var result = new ArrayList<String>(); int depth = 0, copied = 0;
        for (var token : tokens(text)) if (!token.quoted()) {
            if (token.text().equals("(")) depth++; else if (token.text().equals(")")) depth--;
            else if (depth == 0 && token.text().equalsIgnoreCase(separator)) { result.add(text.substring(copied, token.start()).strip()); copied = token.end(); }
        }
        result.add(text.substring(copied).strip()); return result;
    }
    private static int topLevelWord(String text, String word, int start) { int depth = 0; for (var token : tokens(text)) { if (token.start() < start || token.quoted()) continue; if (token.text().equals("(")) depth++; else if (token.text().equals(")")) depth--; else if (depth == 0 && token.text().equalsIgnoreCase(word)) return token.start(); } throw new IllegalArgumentException("Не найден " + word); }
    private static void validateResultNames(List<Token> tokens, String text) {
        for (int start = 0; start < tokens.size(); start++) {
            if (tokens.get(start).quoted() || !tokens.get(start).text().equalsIgnoreCase("select")) continue;
            var names = new HashSet<String>(); int depth = 0;
            for (int index = start + 1; index < tokens.size(); index++) {
                var token = tokens.get(index); if (token.quoted()) continue;
                if (token.text().equals("(")) { depth++; continue; }
                if (token.text().equals(")")) { if (depth == 0) break; depth--; continue; }
                if (depth == 0 && token.text().equalsIgnoreCase("from")) break;
                if (depth == 0 && token.text().equalsIgnoreCase("as") && index + 1 < tokens.size()) {
                    var alias = tokens.get(index + 1);
                    if (!names.add(alias.text().toLowerCase(Locale.ROOT)))
                        throw located(text, alias.start(), "Повторное имя колонки SELECT: " + alias.text());
                }
            }
        }
    }

    private static IllegalArgumentException located(String text, int position, String message) { int line = 1, column = 1; for (int index = 0; index < position; index++) if (text.charAt(index) == '\n') { line++; column = 1; } else column++; return new IllegalArgumentException("Строка " + line + ", столбец " + column + ": " + message); }
    private static List<Token> tokens(String text) {
        var result = new ArrayList<Token>();
        for (int index = 0; index < text.length();) {
            char character = text.charAt(index); if (Character.isWhitespace(character)) { index++; continue; }
            if (text.startsWith("--", index)) { while (index < text.length() && text.charAt(index) != '\n') index++; continue; }
            if (text.startsWith("/*", index)) { int end = text.indexOf("*/", index + 2); if (end < 0) throw located(text, index, "Не закрыт комментарий"); index = end + 2; continue; }
            int start = index++; boolean quoted = character == '\'';
            if (quoted) {
                boolean closed = false;
                while (index < text.length()) if (text.charAt(index++) == '\'') { if (index < text.length() && text.charAt(index) == '\'') index++; else { closed = true; break; } }
                if (!closed) throw located(text, start, "Не закрыта строка");
            } else if (Character.isJavaIdentifierStart(character) || character == ':') {
                while (index < text.length() && Character.isJavaIdentifierPart(text.charAt(index))) index++;
            } else if (Character.isDigit(character)) { while (index < text.length() && (Character.isDigit(text.charAt(index)) || text.charAt(index) == '.')) index++; }
            else if ((character == '<' || character == '>' || character == '!') && index < text.length() && (text.charAt(index) == '=' || character == '<' && text.charAt(index) == '>')) index++;
            result.add(new Token(text.substring(start, index), start, index, quoted));
        }
        return result;
    }
    private static VisualQueryDefinition withParameters(VisualQueryDefinition definition, List<VisualQueryDefinition.Parameter> parameters) {
        return new VisualQueryDefinition(definition.version(), definition.entityName(), definition.entityAlias(), definition.selectFields(), definition.joins(),
            definition.groupBy(), definition.aggregates(), definition.expressions(), definition.having(), definition.where(), parameters, definition.orders(), definition.subqueries());
    }
}
