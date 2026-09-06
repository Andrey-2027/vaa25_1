package org.ipro.reportstudio.query;

import org.ipro.filtergrid.filter.FilterCondition;
import org.ipro.filtergrid.filter.FilterConditionNode;
import org.ipro.filtergrid.filter.FilterDataType;
import org.ipro.filtergrid.filter.FilterGroup;
import org.ipro.filtergrid.filter.FilterNode;
import org.ipro.filtergrid.filter.FilterOperator;
import org.ipro.filtergrid.filter.FilterParameterRef;
import org.ipro.reportstudio.data.QueryField;
import org.ipro.reportstudio.query.CaseBranch;
import org.ipro.reportstudio.query.CaseCondition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Обратный разбор текста JPQL в структуру визуального запроса
 * ({@link VisualQueryDefinition}) — операция, обратная {@link VisualQueryCompiler}.
 *
 * <p>Надёжно восстанавливает подмножество, которое генерирует сам конструктор,
 * плюс простые рукописные запросы: select-поля и агрегаты (SUM/AVG/MIN/MAX/COUNT),
 * from с ассоциативными и независимыми JOIN (включая ON), where/group by/having/
 * order by. Неподдерживаемые конструкции (CASE, арифметика, функции вроде CONCAT,
 * несколько корней FROM, подзапросы, DISTINCT) пропускаются с перечнем в
 * {@link Parsed#warnings()}.</p>
 *
 * <p>Значения WHERE-литералов в тексте — только :visualFilter_N; их значения
 * восстанавливаются из сохранённого черновика (позиционно, при совпадении пути
 * условия), иначе значение остаётся пустым — пользователь заполнит его на вкладке
 * «Условия».</p>
 */
public final class VisualQueryTextParser {

    /** Результат разбора: определение (null, если разобрать не удалось) и предупреждения. */
    public record Parsed(VisualQueryDefinition definition, List<String> warnings) { }

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final List<String> AGGREGATE_FUNCTIONS = List.of("sum", "avg", "min", "max", "count");

    private final QueryBuilderMetadataCatalog catalog;
    private final List<String> warnings = new ArrayList<>();
    private VisualQueryPackage.VirtualCatalog virtualCatalog = VisualQueryPackage.VirtualCatalog.empty();
    private final List<VisualQueryDefinition.Join> joinsAccumulator = new ArrayList<>();
    private final List<VisualQueryDefinition.Expression> expressionsAccumulator = new ArrayList<>();
    /** Подзапросы, найденные при разборе WHERE (имена sub1, sub2, … в порядке появления). */
    private final List<VisualQueryDefinition.Subquery> subqueriesAccumulator = new ArrayList<>();

    public VisualQueryTextParser(QueryBuilderMetadataCatalog catalog) {
        this.catalog = catalog;
    }

    /** Результат разбора пакета WITH. */
    public record PackageParsed(VisualQueryPackage queryPackage, List<String> warnings) { }

    public PackageParsed parsePackage(String jpql) {
        warnings.clear();
        virtualCatalog = VisualQueryPackage.VirtualCatalog.empty();
        if (jpql == null || jpql.isBlank()) return new PackageParsed(null, List.of());
        String text = jpql.trim();
        if (!startsWithWord(text, "with")) {
            Parsed parsed = parse(text, null);
            return new PackageParsed(parsed.definition() == null ? null
                    : new VisualQueryPackage(List.of(), parsed.definition()), List.copyOf(warnings));
        }
        List<String> cteWarnings = new ArrayList<>();
        try {
            int mainStart = findMainSelect(text);
            if (mainStart < 0) throw new IllegalArgumentException("После WITH не найден основной SELECT");
            String withBody = text.substring(4, mainStart).trim();
            List<String> declarations = splitTopLevel(withBody, ',');
            List<VisualQueryPackage.Cte> ctes = new ArrayList<>();
            Map<String, QueryBuilderMetadataCatalog.Entity> virtual = new LinkedHashMap<>();
            for (String declaration : declarations) {
                int as = indexOfTopLevelAs(declaration);
                if (as < 0) throw new IllegalArgumentException("CTE не содержит AS: " + declaration);
                String name = declaration.substring(0, as).trim();
                String body = declaration.substring(as + 2).trim();
                if (!body.startsWith("(") || !body.endsWith(")")) {
                    warnings.add("CTE «" + name + "» имеет неподдерживаемый формат тела.");
                    continue;
                }
                String cteQuery = body.substring(1, body.length() - 1).trim();
                VisualQueryTextParser nested = new VisualQueryTextParser(catalog);
                nested.virtualCatalog = virtualCatalog;
                Parsed parsed = nested.parse(cteQuery, null);
                // Предупреждения CTE собираются отдельно: parse основного запроса ниже
                // очищает this.warnings (тот же список), и они бы потерялись.
                cteWarnings.addAll(parsed.warnings());
                if (parsed.definition() == null) continue;
                VisualQueryDefinition definition = parsed.definition();
                VisualQueryPackage.Source source = virtualCatalog.contains(definition.entityName())
                        ? new VisualQueryPackage.CteSource(definition.entityAlias(), definition.entityName())
                        : new VisualQueryPackage.EntitySource(definition.entityName(), definition.entityAlias());
                ctes.add(new VisualQueryPackage.Cte(name, source, definition));
                if (catalog != null) {
                    VisualQueryPackage.VirtualEntity entity = VisualQueryPackage.VirtualEntity.from(
                            name, definition, catalog, virtualCatalog);
                    virtualCatalog = virtualCatalog.add(entity);
                }
            }
            this.virtualCatalog = virtualCatalog;
            Parsed main = parse(text.substring(mainStart), null);
            List<String> all = new ArrayList<>(cteWarnings);
            all.addAll(main.warnings());
            return new PackageParsed(main.definition() == null ? null
                    : new VisualQueryPackage(ctes, main.definition()), List.copyOf(all));
        } catch (RuntimeException error) {
            warnings.add("Не удалось разобрать пакет WITH: " + message(error));
            List<String> all = new ArrayList<>(warnings);
            all.addAll(cteWarnings);
            return new PackageParsed(null, List.copyOf(all));
        }
    }

    private static boolean startsWithWord(String text, String word) {
        return text.regionMatches(true, 0, word, 0, word.length())
                && (text.length() == word.length() || !Character.isJavaIdentifierPart(text.charAt(word.length())));
    }

    private static int findMainSelect(String text) {
        int depth = 0; boolean quote = false;
        for (int i = 4; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') quote = !quote;
            if (quote) continue;
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0 && (i == 0 || !Character.isJavaIdentifierPart(text.charAt(i - 1)))
                    && text.regionMatches(true, i, "select", 0, 6)
                    && (i + 6 == text.length() || !Character.isJavaIdentifierPart(text.charAt(i + 6)))) return i;
        }
        return -1;
    }

    private static int indexOfTopLevelAs(String text) {
        int depth = 0; boolean quote = false;
        for (int i = 0; i + 1 < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') quote = !quote;
            if (quote) continue;
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0 && text.regionMatches(true, i, "as", 0, 2)
                    && (i == 0 || !Character.isJavaIdentifierPart(text.charAt(i - 1)))
                    && (i + 2 == text.length() || !Character.isJavaIdentifierPart(text.charAt(i + 2)))) return i;
        }
        return -1;
    }

    public Parsed parse(String jpql, VisualQueryDefinition savedForValues) {
        warnings.clear();
        subqueriesAccumulator.clear();
        if (jpql == null || jpql.isBlank()) {
            return new Parsed(null, warnings);
        }
        if (catalog == null) {
            warnings.add("Каталог недоступен — разбор текста невозможен.");
            return new Parsed(null, warnings);
        }
        try {
            return doParse(jpql.trim(), savedForValues);
        } catch (RuntimeException broken) {
            warnings.add("Не удалось разобрать текст: " + message(broken));
            return new Parsed(null, warnings);
        }
    }

    /** Результат разбора одного вычисляемого выражения. */
    public record ParsedExpression(VisualQueryExpression expression, List<String> warnings) { }

    /**
     * Разбор одного вычисляемого выражения («s.quantity * 2 + 1», «case when … end»)
     * в AST — для редактора вычисляемых полей конструктора. Вызывать на свежем
     * экземпляре парсера: накопленные предупреждения сбрасываются.
     */
    public ParsedExpression parseExpression(String text, Map<String, QueryBuilderMetadataCatalog.Entity> aliases) {
        warnings.clear();
        if (text == null || text.isBlank()) {
            return new ParsedExpression(null, List.of("Выражение пустое"));
        }
        VisualQueryExpression ast = parseExpressionAst(text.trim(), aliases);
        if (ast == null && warnings.isEmpty()) {
            warnings.add("Не удалось разобрать выражение (поддерживаются поля, арифметика,"
                    + " функции allow-list, CASE и литералы)");
        }
        return new ParsedExpression(ast, List.copyOf(warnings));
    }

    // === Разбор ===

    private Parsed doParse(String text, VisualQueryDefinition saved) {
        JpqlLexer.Token[] tokens = JpqlLexer.tokenize(text).toArray(JpqlLexer.Token[]::new);
        Segments segments = scanClauses(tokens, text);        Map<String, QueryBuilderMetadataCatalog.Entity> aliases = new LinkedHashMap<>();
        String entityName = parseFrom(segments.from(), aliases);

        List<VisualQueryDefinition.SelectField> selects = new ArrayList<>();
        List<VisualQueryDefinition.Aggregate> aggregates = new ArrayList<>();
        List<VisualQueryDefinition.Expression> expressions = new ArrayList<>();
        parseSelect(segments.select(), aliases, selects, aggregates, expressions);

        List<String> groupBy = parseGroupBy(segments.groupBy(), aliases, selects, aggregates);
        VisualQueryDefinition.Having having = parseHaving(segments.having(), aggregates);
        List<VisualQueryOrder> orders = parseOrderBy(segments.orderBy(), aliases, selects);
        FilterNode where = parseWhere(segments.where(), aliases, saved);

        if (selects.isEmpty() && aggregates.isEmpty() && expressions.isEmpty()) {
            warnings.add("В SELECT не найдено полей или агрегатов конструктора.");
            return new Parsed(null, warnings);
        }
        if (!hasRoot(aliases)) {
            warnings.add("Не найдена корневая сущность FROM, разрешённая каталогом.");
            return new Parsed(null, warnings);
        }

        VisualQueryDefinition definition = new VisualQueryDefinition(
                VisualQueryDefinition.CURRENT_VERSION, entityName, rootAliasOf(aliases),
                List.copyOf(selects), List.copyOf(joinsOf(aliases)), groupBy,
                List.copyOf(aggregates), List.copyOf(expressions), having, where, List.of(), orders,
                List.copyOf(subqueriesAccumulator));
        return new Parsed(definition, warnings);
    }

    // === Секции ===

    /** Границы ключевых слов на верхнем уровне (вне кавычек и скобок). */
    private record Segments(String select, String from, String where,
                            String groupBy, String having, String orderBy) { }

    private Segments scanClauses(JpqlLexer.Token[] tokens, String text) {
        int[] start = new int[] { -1, -1, -1, -1, -1, -1 };
        int lastAssigned = -1;
        int depth = 0;
        for (int i = 0; i < tokens.length; i++) {
            JpqlLexer.Token token = tokens[i];
            if (token.type() == JpqlLexer.Type.LPAREN) depth++;
            if (token.type() == JpqlLexer.Type.RPAREN) depth--;
            if (depth > 0 || token.type() != JpqlLexer.Type.WORD) continue;
            String word = token.value().toLowerCase(Locale.ROOT);
            int clause = -1;
            if (word.equals("select")) clause = 0;
            else if (word.equals("from")) clause = 1;
            else if (word.equals("where")) clause = 2;
            else if (word.equals("group") && i + 1 < tokens.length && tokens[i + 1].word("by")) clause = 3;
            else if (word.equals("having")) clause = 4;
            else if (word.equals("order") && i + 1 < tokens.length && tokens[i + 1].word("by")) clause = 5;
            if (clause >= 0 && start[clause] == -1 && token.position() > lastAssigned) {
                start[clause] = token.position();
                lastAssigned = token.position();
            }
        }
        if (start[0] == -1 || start[1] == -1) {
            throw new IllegalArgumentException("Не найдены секции select/from");
        }
        String select = body(text, endOfKeyword(text, start[0], 6), nextBoundary(start, 0));
        String from = body(text, endOfKeyword(text, start[1], 4), nextBoundary(start, 1));
        String where = start[2] < 0 ? null : body(text, endOfKeyword(text, start[2], 5), nextBoundary(start, 2));
        String groupBy = start[3] < 0 ? null : body(text, endOfKeyword(text, start[3], 8), nextBoundary(start, 3));
        String having = start[4] < 0 ? null : body(text, endOfKeyword(text, start[4], 6), nextBoundary(start, 4));
        String orderBy = start[5] < 0 ? null : body(text, endOfKeyword(text, start[5], 8), -1);
        return new Segments(select, from, where, groupBy, having, orderBy);
    }

    /** Ближайшая граница следующей объявленной секции (секции могут отсутствовать). */
    private static int nextBoundary(int[] start, int from) {
        int best = -1;
        for (int j = from + 1; j < start.length; j++) {
            if (start[j] >= 0 && (best < 0 || start[j] < best)) best = start[j];
        }
        return best;
    }

    /** Конец последующего ключевого слова (позиция, с которой начинается тело секции). */
    private static int endOfKeyword(String text, int keywordStart, int keywordLength) {
        int end = keywordStart + keywordLength;
        while (end < text.length() && Character.isWhitespace(text.charAt(end))) end++;
        return end;
    }

    private static int boundary(int[] start, int nextClause) {
        return start[nextClause] < 0 ? -1 : start[nextClause];
    }

    private static String body(String text, int begin, int end) {
        return text.substring(begin, end < 0 ? text.length() : end).trim();
    }

    private static String message(RuntimeException error) {
        String text = error.getMessage();
        return text == null || text.isBlank() ? "неизвестная ошибка" : text;
    }

    // === SELECT: поля и агрегаты ===

    private void parseSelect(String selectBody, Map<String, QueryBuilderMetadataCatalog.Entity> aliases,
                             List<VisualQueryDefinition.SelectField> selects,
                             List<VisualQueryDefinition.Aggregate> aggregates,
                             List<VisualQueryDefinition.Expression> expressions) {
        String body = selectBody;
        if (body.toLowerCase(Locale.ROOT).startsWith("distinct")) {
            warnings.add("DISTINCT игнорируется — конструктор его не строит.");
            body = body.substring("distinct".length()).trim();
        }
        for (String item : splitTopLevel(body, ',')) {
            parseSelectItem(item.trim(), aliases, selects, aggregates, expressions);
        }
    }

    private void parseSelectItem(String item, Map<String, QueryBuilderMetadataCatalog.Entity> aliases,
                                 List<VisualQueryDefinition.SelectField> selects,
                                 List<VisualQueryDefinition.Aggregate> aggregates,
                                 List<VisualQueryDefinition.Expression> expressions) {
        if (item.isBlank()) return;
        JpqlLexer.Token[] tokens = JpqlLexer.tokenize(item).toArray(JpqlLexer.Token[]::new);
        // Форма: выражение [as псевдоним]; наш компилятор всегда даёт «expr as alias».
        String expression = item;
        String resultName = null;
        for (int i = tokens.length - 2; i >= 0; i--) {
            if (tokens[i].word("as") && i + 1 < tokens.length
                    && tokens[i + 1].type() == JpqlLexer.Type.WORD) {
                expression = item.substring(0, tokens[i].position()).trim();
                resultName = tokens[i + 1].value();
                break;
            }
        }
        expression = expression.trim();
        if (expression.startsWith("(") && expression.endsWith(")")) {
            expression = expression.substring(1, expression.length() - 1).trim();
        }
        if (resultName != null && !IDENTIFIER.matcher(resultName).matches()) {
            warnings.add("Псевдоним «" + resultName + "» не является идентификатором — элемент пропущен.");
            return;
        }
        String functionName = functionOf(expression);
        List<String> path = functionName != null ? functionPath(expression) : plainPath(expression);
        if (path == null) {
            // Не простое поле и не агрегат — пробуем разобрать как вычисляемое выражение
            // (арифметика, функции allow-list, CASE).
            VisualQueryExpression ast = parseExpressionAst(expression, aliases);
            if (ast == null) {
                warnings.add("Выражение «" + item + "» не восстановимо (поддерживаются поля, агрегаты и"
                        + " вычисляемые выражения: арифметика, функции, CASE).");
                return;
            }
            expressions.add(new VisualQueryDefinition.Expression(
                    uniqueName(resultName, "expr", selects, aggregates, expressions), ast));
            return;
        }
        if (path.size() == 1) {
            // count(<alias>) — подсчёт строк по сущности алиаса (как в 1С «количество записей»):
            // компилируется как COUNT_ROWS по alias.id.
            if (functionName != null && functionName.equals("count") && aliases.containsKey(path.get(0))) {
                aggregates.add(new VisualQueryDefinition.Aggregate("COUNT_ROWS", path.get(0) + ".id",
                        uniqueName(resultName, "count_rows", selects, aggregates, expressions)));
                return;
            }
            warnings.add("Поле «" + item + "» без алиаса таблицы — элемент пропущен.");
            return;
        }
        String alias = path.get(0);
        QueryBuilderMetadataCatalog.Entity entity = aliases.get(alias);
        if (entity == null) {
            warnings.add("Поле «" + item + "»: алиас «" + alias + "» не найден — элемент пропущен.");
            return;
        }
        QueryBuilderMetadataCatalog.Entity target = deepPathTarget(path, aliases);
        if (target == null) {
            warnings.add("Путь «" + item + "» не разрешается по связям каталога — элемент пропущен.");
            return;
        }
        String field = path.get(path.size() - 1);
        QueryBuilderMetadataCatalog.Association association = associationOf(target, field);
        QueryBuilderMetadataCatalog.Field plainField = fieldOf(target, field);
        String joinedPath = String.join(".", path);
        if (functionName == null) {
            if (plainField == null && association == null && !field.equals("id")) {
                warnings.add("Поле «" + item + "» не найдено в каталоге — элемент пропущен.");
                return;
            }
            selects.add(new VisualQueryDefinition.SelectField(joinedPath,
                    resultName == null ? field : resultName));
            return;
        }
        // Агрегат
        if (plainField == null && association == null) {
            boolean isCountRows = functionName.equals("count") && field.equalsIgnoreCase("id")
                    && fieldOf(entity, "id") == null;
            if (isCountRows) {
                aggregates.add(new VisualQueryDefinition.Aggregate("COUNT_ROWS", joinedPath,
                        uniqueName(resultName, "count_rows", selects, aggregates, expressions)));
                return;
            }
            warnings.add("Агрегат «" + item + "» по неизвестному полю — элемент пропущен.");
            return;
        }
        Class<?> javaType = plainField == null ? association.targetType() : plainField.javaType();
        if ((functionName.equals("sum") || functionName.equals("avg")) && !QueryField.isNumber(javaType)) {
            warnings.add("Агрегат «" + item + "» недоступен для нечислового поля — элемент пропущен.");
            return;
        }
        aggregates.add(new VisualQueryDefinition.Aggregate(functionName.toUpperCase(Locale.ROOT),
                joinedPath,
                uniqueName(resultName, functionName + "_" + field, selects, aggregates, expressions)));
    }

    /** Имя без коллизий: заданное, если свободно; иначе база с номером. */
    private static String uniqueName(String requested, String fallback,
                                     List<VisualQueryDefinition.SelectField> selects,
                                     List<VisualQueryDefinition.Aggregate> aggregates,
                                     List<VisualQueryDefinition.Expression> expressions) {
        java.util.Set<String> taken = new java.util.LinkedHashSet<>();
        selects.forEach(field -> taken.add(field.resultName()));
        aggregates.forEach(aggregate -> taken.add(aggregate.resultName()));
        expressions.forEach(expression -> taken.add(expression.resultName()));
        if (requested != null && IDENTIFIER.matcher(requested).matches() && !taken.contains(requested)) {
            return requested;
        }
        String base = fallback;
        int index = 2;
        while (taken.contains(base)) base = fallback + index++;
        return base;
    }

    /** Голый путь «alias.field» без скобок и функций; иначе null. */
    private static List<String> plainPath(String expression) {
        if (expression.indexOf('(') >= 0) return null;
        if (!expression.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")) return null;
        return List.of(expression.split("\\."));
    }

    /** Целевая сущность пути «alias.assoc…field»: проход по ассоциациям; null, если путь не разрешается. */
    private QueryBuilderMetadataCatalog.Entity deepPathTarget(List<String> path,
                                                              Map<String, QueryBuilderMetadataCatalog.Entity> aliases) {
        QueryBuilderMetadataCatalog.Entity entity = aliases.get(path.get(0));
        for (int i = 1; i < path.size() - 1; i++) {
            var association = associationOf(entity, path.get(i));
            if (association == null || association.targetType() == null) return null;
            entity = entityByType(association.targetType());
            if (entity == null) return null;
        }
        return entity;
    }

    /** Путь «alias.assoc…field» селектируем: промежуточные сегменты — ассоциации, последний — поле/связь/id. */
    private boolean selectableDeepPath(String path, Map<String, QueryBuilderMetadataCatalog.Entity> aliases) {
        if (path == null
                || !path.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")
                || !path.contains(".")) {
            return false;
        }
        String[] segments = path.split("\\.");
        QueryBuilderMetadataCatalog.Entity entity = aliases.get(segments[0]);
        for (int i = 1; i < segments.length; i++) {
            if (i == segments.length - 1) return selectable(entity, segments[i]);
            var association = associationOf(entity, segments[i]);
            if (association == null || association.targetType() == null) return false;
            entity = entityByType(association.targetType());
            if (entity == null) return false;
        }
        return true;
    }

    // === Вычисляемые выражения: арифметика, функции allow-list, CASE ===

    /** Разбор текста выражения в AST; null, если конструкция не поддерживается. */
    private VisualQueryExpression parseExpressionAst(String text,
                                                     Map<String, QueryBuilderMetadataCatalog.Entity> aliases) {
        try {
            JpqlLexer.Token[] tokens = JpqlLexer.tokenize(text).toArray(JpqlLexer.Token[]::new);
            int[] cursor = new int[] { 0 };
            VisualQueryExpression ast = parseExprAst(tokens, cursor, aliases);
            return ast == null || cursor[0] != tokens.length ? null : ast;
        } catch (RuntimeException broken) {
            return null;
        }
    }

    /** Выражение: слагаемые через + / -. */
    private VisualQueryExpression parseExprAst(JpqlLexer.Token[] tokens, int[] cursor,
                                               Map<String, QueryBuilderMetadataCatalog.Entity> aliases) {
        VisualQueryExpression left = parseTermAst(tokens, cursor, aliases);
        while (left != null && cursor[0] < tokens.length
                && tokens[cursor[0]].type() == JpqlLexer.Type.OP
                && (tokens[cursor[0]].value().equals("+") || tokens[cursor[0]].value().equals("-"))) {
            VisualQueryExpression.Operator operator = tokens[cursor[0]].value().equals("+")
                    ? VisualQueryExpression.Operator.ADD : VisualQueryExpression.Operator.SUBTRACT;
            cursor[0]++;
            VisualQueryExpression right = parseTermAst(tokens, cursor, aliases);
            if (right == null) return null;
            left = new VisualQueryExpression.Binary(operator, left, right);
        }
        return left;
    }

    /** Слагаемое: множители через * и /. */
    private VisualQueryExpression parseTermAst(JpqlLexer.Token[] tokens, int[] cursor,
                                               Map<String, QueryBuilderMetadataCatalog.Entity> aliases) {
        VisualQueryExpression left = parseFactorAst(tokens, cursor, aliases);
        while (left != null && cursor[0] < tokens.length
                && tokens[cursor[0]].type() == JpqlLexer.Type.OP
                && (tokens[cursor[0]].value().equals("*") || tokens[cursor[0]].value().equals("/"))) {
            VisualQueryExpression.Operator operator = tokens[cursor[0]].value().equals("*")
                    ? VisualQueryExpression.Operator.MULTIPLY : VisualQueryExpression.Operator.DIVIDE;
            cursor[0]++;
            VisualQueryExpression right = parseFactorAst(tokens, cursor, aliases);
            if (right == null) return null;
            left = new VisualQueryExpression.Binary(operator, left, right);
        }
        return left;
    }

    private VisualQueryExpression parseFactorAst(JpqlLexer.Token[] tokens, int[] cursor,
                                                 Map<String, QueryBuilderMetadataCatalog.Entity> aliases) {
        if (cursor[0] >= tokens.length) return null;
        JpqlLexer.Token token = tokens[cursor[0]];
        if (token.type() == JpqlLexer.Type.NUMBER) {
            cursor[0]++;
            return new VisualQueryExpression.Literal(Double.parseDouble(token.value().replace(',', '.')));
        }
        if (token.type() == JpqlLexer.Type.STRING) {
            cursor[0]++;
            return new VisualQueryExpression.Literal(token.value());
        }
        if (token.type() == JpqlLexer.Type.LPAREN) {
            cursor[0]++;
            VisualQueryExpression inner = parseExprAst(tokens, cursor, aliases);
            if (inner == null || cursor[0] >= tokens.length
                    || tokens[cursor[0]].type() != JpqlLexer.Type.RPAREN) return null;
            cursor[0]++;
            return inner;
        }
        if (token.type() != JpqlLexer.Type.WORD) return null;
        String word = token.value();
        String lower = word.toLowerCase(Locale.ROOT);
        if (lower.equals("true") || lower.equals("false")) {
            cursor[0]++;
            return new VisualQueryExpression.Literal(Boolean.parseBoolean(lower));
        }
        if (lower.equals("null")) {
            cursor[0]++;
            return new VisualQueryExpression.Literal(null);
        }
        if (lower.equals("case")) {
            return parseCaseAst(tokens, cursor, aliases);
        }
        if (word.startsWith(":")) {
            warnings.add("Параметры внутри вычисляемых полей пока не поддерживаются — элемент пропущен.");
            return null;
        }
        // Функция allow-list или ссылка на поле.
        if (cursor[0] + 1 < tokens.length && tokens[cursor[0] + 1].type() == JpqlLexer.Type.LPAREN) {
            if (!VisualQueryCompiler.allowedFunctionNames().contains(word.toUpperCase(Locale.ROOT))) {
                warnings.add("Функция «" + word + "» не разрешена — выражение пропущено.");
                return null;
            }
            cursor[0] += 2;
            List<VisualQueryExpression> arguments = new ArrayList<>();
            if (cursor[0] < tokens.length && tokens[cursor[0]].type() == JpqlLexer.Type.RPAREN) {
                cursor[0]++;
                return new VisualQueryExpression.FunctionCall(word, arguments);
            }
            while (cursor[0] < tokens.length) {
                VisualQueryExpression argument = parseExprAst(tokens, cursor, aliases);
                if (argument == null) return null;
                arguments.add(argument);
                if (cursor[0] < tokens.length && tokens[cursor[0]].type() == JpqlLexer.Type.COMMA) {
                    cursor[0]++;
                    continue;
                }
                break;
            }
            if (cursor[0] >= tokens.length || tokens[cursor[0]].type() != JpqlLexer.Type.RPAREN) return null;
            cursor[0]++;
            return new VisualQueryExpression.FunctionCall(word, arguments);
        }
        if (!word.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")) return null;
        cursor[0]++;
        return selectableFieldRef(word, aliases);
    }

    /** FieldRef по пути с проверкой каталога: «поле» (корень) или «alias.поле». */
    private VisualQueryExpression.FieldRef selectableFieldRef(String path,
                                                              Map<String, QueryBuilderMetadataCatalog.Entity> aliases) {
        int dot = path.indexOf('.');
        if (dot < 0) {
            var root = aliases.get(rootAliasOf(aliases));
            return selectable(root, path) ? new VisualQueryExpression.FieldRef(path) : null;
        }
        var entity = aliases.get(path.substring(0, dot));
        String field = path.substring(dot + 1);
        return selectable(entity, field) ? new VisualQueryExpression.FieldRef(path) : null;
    }

    /** Поле доступно для выборки: обычное поле, ассоциация или системный id записи. */
    private static boolean selectable(QueryBuilderMetadataCatalog.Entity entity, String name) {
        return fieldOf(entity, name) != null || associationOf(entity, name) != null || "id".equals(name);
    }

    /** CASE WHEN путь (=/<>/>/>=/< /<= число) | (between число and число) THEN expr … ELSE expr END. */
    private VisualQueryExpression parseCaseAst(JpqlLexer.Token[] tokens, int[] cursor,
                                               Map<String, QueryBuilderMetadataCatalog.Entity> aliases) {
        cursor[0]++; // case
        List<CaseBranch> branches = new ArrayList<>();
        while (cursor[0] < tokens.length && tokens[cursor[0]].word("when")) {
            cursor[0]++;
            if (cursor[0] + 2 >= tokens.length || tokens[cursor[0]].type() != JpqlLexer.Type.WORD) return null;
            String leftPath = tokens[cursor[0]].value();
            if (selectableFieldRef(leftPath, aliases) == null) return null;
            CaseCondition condition;
            cursor[0]++;
            JpqlLexer.Token op = tokens[cursor[0]];
            if (op.word("between")) {
                cursor[0]++;
                Double min = numberToken(tokens, cursor);
                if (min == null || cursor[0] >= tokens.length || !tokens[cursor[0]].word("and")) return null;
                cursor[0]++;
                Double max = numberToken(tokens, cursor);
                if (max == null) return null;
                condition = new CaseCondition.Between(leftPath, min, max);
            } else {
                CaseCondition.CmpOp cmpOp = cmpOpOf(op);
                if (cmpOp == null) return null;
                cursor[0]++;
                Double value = numberToken(tokens, cursor);
                if (value == null) return null;
                condition = new CaseCondition.Cmp(leftPath, cmpOp, value);
            }
            if (cursor[0] >= tokens.length || !tokens[cursor[0]].word("then")) return null;
            cursor[0]++;
            VisualQueryExpression result = parseExprAst(tokens, cursor, aliases);
            if (result == null) return null;
            branches.add(new CaseBranch(condition, result));
        }
        if (branches.isEmpty()) return null;
        VisualQueryExpression elseResult = null;
        if (cursor[0] < tokens.length && tokens[cursor[0]].word("else")) {
            cursor[0]++;
            elseResult = parseExprAst(tokens, cursor, aliases);
            if (elseResult == null) return null;
        }
        if (cursor[0] >= tokens.length || !tokens[cursor[0]].word("end")) return null;
        cursor[0]++;
        return new VisualQueryExpression.Case(branches, elseResult);
    }

    private static Double numberToken(JpqlLexer.Token[] tokens, int[] cursor) {
        if (cursor[0] >= tokens.length || tokens[cursor[0]].type() != JpqlLexer.Type.NUMBER) return null;
        Double value = Double.parseDouble(tokens[cursor[0]].value().replace(',', '.'));
        cursor[0]++;
        return value;
    }

    private static CaseCondition.CmpOp cmpOpOf(JpqlLexer.Token token) {
        if (token.type() != JpqlLexer.Type.OP) return null;
        return switch (token.value()) {
            case "=" -> CaseCondition.CmpOp.EQ;
            case "<>", "!=" -> CaseCondition.CmpOp.NE;
            case ">" -> CaseCondition.CmpOp.GT;
            case ">=" -> CaseCondition.CmpOp.GE;
            case "<" -> CaseCondition.CmpOp.LT;
            case "<=" -> CaseCondition.CmpOp.LE;
            default -> null;
        };
    }

    /** Имя функции, если выражение — это func(аргумент) из allow-list агрегатов; иначе null. */
    private static String functionOf(String expression) {
        int open = expression.indexOf('(');
        if (open <= 0 || !expression.endsWith(")")) return null;
        String name = expression.substring(0, open).trim().toLowerCase(Locale.ROOT);
        return AGGREGATE_FUNCTIONS.contains(name) ? name : null;
    }

    /** Аргумент агрегата/путь поля: «alias.field»; null для прочих выражений. */
    private static List<String> functionPath(String expression) {
        String core = expression;
        int open = core.indexOf('(');
        if (open >= 0) {
            if (!core.endsWith(")") || core.indexOf(')') != core.length() - 1) return null;
            core = core.substring(open + 1, core.length() - 1).trim();
            if (core.toLowerCase(Locale.ROOT).startsWith("distinct ")) return null;
        }
        if (core.isEmpty() || !core.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")) {
            return null;
        }
        return List.of(core.split("\\."));
    }

    // === FROM: корень и JOIN ===

    private String parseFrom(String fromBody, Map<String, QueryBuilderMetadataCatalog.Entity> aliases) {
        List<String> clauses = splitJoins(fromBody);
        String root = clauses.get(0).trim();
        JpqlLexer.Token[] rootTokens = JpqlLexer.tokenize(root).toArray(JpqlLexer.Token[]::new);
        if (rootTokens.length < 2 || rootTokens[0].type() != JpqlLexer.Type.WORD
                || rootTokens[1].type() != JpqlLexer.Type.WORD) {
            throw new IllegalArgumentException("Ожидался FROM <Сущность> <alias>");
        }
        String entityToken = rootTokens[0].value();
        String alias = rootTokens[1].value();
        if (!IDENTIFIER.matcher(alias).matches()) {
            throw new IllegalArgumentException("Недопустимый alias корня: " + alias);
        }
        QueryBuilderMetadataCatalog.Entity entity = entityOf(entityToken);
        if (entity == null) {
            throw new IllegalArgumentException("Сущность «" + entityToken + "» не разрешена каталогом");
        }
        aliases.put(alias, entity);
        if (rootTokens.length > 2) {
            warnings.add("Лишние элементы после FROM <Сущность> <alias> игнорируются.");
        }
        for (int i = 1; i < clauses.size(); i++) {
            parseJoin(clauses.get(i).trim(), aliases);
        }
        if (clauses.size() > 1 && fromBody.indexOf(',') >= 0 && !splitTopLevel(fromBody, ',').isEmpty()) {
            warnings.add("Несколько корней FROM не поддерживаются — разобран только первый.");
        }
        return entity.entityName();
    }

    /** Разделяет тело FROM на корень и JOIN-фразы (по join/left [outer] join на верхнем уровне). */
    private static List<String> splitJoins(String body) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inQuote = false;
        int i = 0;
        while (i < body.length()) {
            char c = body.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
                current.append(c);
                i++;
                continue;
            }
            if (!inQuote) {
                if (c == '(') depth++;
                if (c == ')') depth--;
                if (depth == 0 && !inQuote && Character.isLetter(c)) {
                    int joinPhrase = joinKeywordLength(body, i);
                    if (joinPhrase > 0) {
                        // Начинается новая JOIN-фраза: ключевое слово остаётся В начале фразы,
                        // чтобы parseJoin видел left/inner.
                        if (!current.toString().isBlank()) {
                            result.add(current.toString().trim());
                        }
                        current.setLength(0);
                        current.append(body, i, i + joinPhrase);
                        i += joinPhrase;
                        continue;
                    }
                }
            }
            current.append(c);
            i++;
        }
        if (!current.toString().isBlank()) result.add(current.toString().trim());
        return result;
    }

    private static int skipSpaces(String text, int from) {
        while (from < text.length() && Character.isWhitespace(text.charAt(from))) from++;
        return from;
    }

    /** Длина JOIN-фразы с позиции: «join», «left join», «left outer join»; -1, если это не JOIN. */
    private static int joinKeywordLength(String body, int from) {
        String word = readWord(body, from);
        String lower = word.toLowerCase(Locale.ROOT);
        if (lower.equals("join")) return word.length();
        if (!lower.equals("left")) return -1;
        int i = skipSpaces(body, from + word.length());
        String second = readWord(body, i);
        if (second.equalsIgnoreCase("join")) return i + second.length() - from;
        if (!second.equalsIgnoreCase("outer")) return -1;
        int j = skipSpaces(body, i + second.length());
        String third = readWord(body, j);
        if (third.equalsIgnoreCase("join")) return j + third.length() - from;
        return -1;
    }

    private static String readWord(String text, int from) {
        int end = from;
        while (end < text.length() && (Character.isJavaIdentifierPart(text.charAt(end))
                || text.charAt(end) == '.')) {
            end++;
        }
        return text.substring(from, end);
    }

    private static boolean nextWordIs(String text, int from, String expected) {
        int i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        return text.regionMatches(true, i, expected, 0, expected.length());
    }

    // === JOIN ===

    private void parseJoin(String joinBody, Map<String, QueryBuilderMetadataCatalog.Entity> aliases) {
        boolean left = false;
        String body = joinBody;
        if (body.toLowerCase(Locale.ROOT).startsWith("left")) {
            left = true;
            body = body.substring("left".length()).trim();
            if (body.toLowerCase(Locale.ROOT).startsWith("outer")) {
                body = body.substring("outer".length()).trim();
            }
        }
        if (body.toLowerCase(Locale.ROOT).startsWith("join")) {
            body = body.substring("join".length()).trim();
        }
        int onIndex = indexOfOn(body);
        String sources = onIndex < 0 ? body : body.substring(0, onIndex).trim();
        String on = onIndex < 0 ? null : body.substring(onIndex + 2).trim();

        JpqlLexer.Token[] tokens = JpqlLexer.tokenize(sources).toArray(JpqlLexer.Token[]::new);
        if (tokens.length < 2 || tokens[0].type() != JpqlLexer.Type.WORD
                || tokens[1].type() != JpqlLexer.Type.WORD) {
            warnings.add("JOIN «" + joinBody + "» не разобран (ожидается источник и alias) — пропущен.");
            return;
        }
        if (tokens.length > 2) {
            warnings.add("Лишние элементы в JOIN «" + joinBody + "» игнорируются.");
        }
        String alias = tokens[1].value();
        if (!IDENTIFIER.matcher(alias).matches()) {
            warnings.add("Недопустимый alias JOIN «" + alias + "» — пропущен.");
            return;
        }
        String source = tokens[0].value();
        int dot = source.indexOf('.');
        VisualQueryDefinition.Join join;
        if (dot > 0 && aliases.containsKey(source.substring(0, dot))) {
            // Ассоциативный JOIN: <родитель>.<связь> <alias>
            String parent = source.substring(0, dot);
            String association = source.substring(dot + 1);
            if (associationOf(aliases.get(parent), association) == null) {
                warnings.add("Связь «" + source + "» не найдена — JOIN пропущен.");
                return;
            }
            join = new VisualQueryDefinition.Join(parent, association, alias,
                    left ? VisualQueryDefinition.JoinKind.LEFT : VisualQueryDefinition.JoinKind.INNER);
        } else {
            // Независимый JOIN: <Сущность> <alias> [on ...]
            QueryBuilderMetadataCatalog.Entity target = entityOf(source);
            if (target == null) {
                warnings.add("Сущность JOIN «" + source + "» не разрешена каталогом — JOIN пропущен.");
                return;
            }
            JoinCondition condition = null;
            if (on == null || on.isBlank()) {
                warnings.add("Независимый JOIN «" + joinBody + "» без условия ON — после разбора задайте его на вкладке «Связи».");
            } else {
                condition = parseOnCondition(on, aliases);
                if (condition == null) {
                    warnings.add("Условие ON «" + on + "» не разобрано — JOIN пропущен.");
                    return;
                }
            }
            join = new VisualQueryDefinition.Join(null, target.entityName(), alias,
                    left ? VisualQueryDefinition.JoinKind.LEFT : VisualQueryDefinition.JoinKind.INNER, condition);
        }
        aliases.put(alias, entityOfJoin(join, aliases));
        joinsAccumulator.add(join);
    }

    /** Позиция слова "on" вне скобок и кавычек. */
    private static int indexOfOn(String body) {
        int depth = 0;
        boolean inQuote = false;
        int i = 0;
        while (i < body.length()) {
            char c = body.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
                i++;
                continue;
            }
            if (!inQuote) {
                if (c == '(') depth++;
                if (c == ')') depth--;
                if (depth == 0 && Character.isLetter(c)) {
                    String word = readWord(body, i);
                    if (word.equalsIgnoreCase("on")) return i;
                    i += Math.max(word.length(), 1);
                    continue;
                }
            }
            i++;
        }
        return -1;
    }

    private JoinCondition parseOnCondition(String on, Map<String, QueryBuilderMetadataCatalog.Entity> aliases) {
        JpqlLexer.Token[] tokens = JpqlLexer.tokenize(on).toArray(JpqlLexer.Token[]::new);
        int[] cursor = new int[] { 0 };
        JoinCondition raw = parseOnExpression(tokens, cursor, aliases);
        if (raw == null || cursor[0] != tokens.length) {
            warnings.add("Условие ON разобрано не полностью: " + on);
        }
        return raw;
    }

    /** ОН-условие: «alias.field = alias.field», группы в скобках с И/ИЛИ. */
    private JoinCondition parseOnExpression(JpqlLexer.Token[] tokens, int[] cursor,
                                            Map<String, QueryBuilderMetadataCatalog.Entity> aliases) {
        List<JoinCondition> items = new ArrayList<>();
        List<JoinLogicalOperator> connectors = new ArrayList<>();
        JoinCondition first = parseOnFactor(tokens, cursor, aliases);
        if (first == null) return null;
        items.add(first);
        while (cursor[0] < tokens.length) {
            JoinLogicalOperator connector = null;
            if (tokens[cursor[0]].word("and")) connector = JoinLogicalOperator.AND;
            else if (tokens[cursor[0]].word("or")) connector = JoinLogicalOperator.OR;
            if (connector == null) break;
            cursor[0]++;
            JoinCondition next = parseOnFactor(tokens, cursor, aliases);
            if (next == null) return null;
            items.add(next);
            connectors.add(connector);
        }
        if (items.size() == 1) return items.get(0);
        if (connectors.stream().distinct().count() > 1) {
            warnings.add("Смешанные И/ИЛИ в ON разобраны как ИЛИ-группы из И-групп.");
            return new JoinCondition.Group(JoinLogicalOperator.OR,
                    groupByOperator(items, connectors, JoinLogicalOperator.AND, JoinLogicalOperator.OR));
        }
        return new JoinCondition.Group(connectors.get(0), items);
    }

    private JoinCondition parseOnFactor(JpqlLexer.Token[] tokens, int[] cursor,
                                        Map<String, QueryBuilderMetadataCatalog.Entity> aliases) {
        if (cursor[0] < tokens.length && tokens[cursor[0]].type() == JpqlLexer.Type.LPAREN) {
            cursor[0]++;
            JoinCondition inner = parseOnExpression(tokens, cursor, aliases);
            if (inner == null || cursor[0] >= tokens.length
                    || tokens[cursor[0]].type() != JpqlLexer.Type.RPAREN) return null;
            cursor[0]++;
            return inner;
        }
        if (cursor[0] + 2 >= tokens.length) return null;
        JpqlLexer.Token left = tokens[cursor[0]];
        JpqlLexer.Token op = tokens[cursor[0] + 1];
        JpqlLexer.Token right = tokens[cursor[0] + 2];
        if (left.type() != JpqlLexer.Type.WORD || op.type() != JpqlLexer.Type.OP
                || right.type() != JpqlLexer.Type.WORD) return null;
        JoinCondition.Operator operator = op.value().equals("=") ? JoinCondition.Operator.EQ
                : (op.value().equals("<>") || op.value().equals("!=") || op.value().equals("<"))
                ? JoinCondition.Operator.NE : null;
        if (operator == null) {
            warnings.add("Оператор ON «" + op.value() + "» не поддерживается (только = и <>)");
            return null;
        }
        if (!fieldReference(left.value()) || !fieldReference(right.value())) {
            warnings.add("Стороны ON должны быть путями «alias.поле»: " + left.value() + " / " + right.value());
            return null;
        }
        cursor[0] += 3;
        return new JoinCondition.Predicate(left.value(), operator, right.value());
    }

    private static boolean fieldReference(String value) {
        return value != null && value.matches("[A-Za-z_][A-Za-z0-9_]*\\.[A-Za-z_][A-Za-z0-9_]*");
    }

    /** Группировка списка по одинаковой связке: вложенные группы другого оператора. */
    private static List<JoinCondition> groupByOperator(List<JoinCondition> items, List<JoinLogicalOperator> connectors,
                                                       JoinLogicalOperator inner, JoinLogicalOperator outer) {
        List<JoinCondition> outerItems = new ArrayList<>();
        List<JoinCondition> innerItems = new ArrayList<>();
        innerItems.add(items.get(0));
        for (int i = 0; i < connectors.size(); i++) {
            if (connectors.get(i) == inner) {
                innerItems.add(items.get(i + 1));
            } else {
                outerItems.add(innerItems.size() == 1 ? innerItems.get(0)
                        : new JoinCondition.Group(inner, innerItems));
                innerItems = new ArrayList<>();
                innerItems.add(items.get(i + 1));
            }
        }
        outerItems.add(innerItems.size() == 1 ? innerItems.get(0) : new JoinCondition.Group(inner, innerItems));
        return outerItems;
    }

    // === WHERE ===

    /**
     * Сырое условие до разрешения значения: литералы — как есть, :параметры —
     * восстанавливаются из сохранённого черновика по имени (visualFilter_N).
     */
    private record RawCond(String path, FilterOperator operator, String value, String valueTo,
                           String paramName, String paramNameTo, boolean like) { }

    /** Ссылка на условие сохранённого черновика: какой слот значения за именем параметра. */
    private record SavedRef(org.ipro.filtergrid.filter.FilterCondition condition, boolean valueSlot) { }

    private record RawGroup(org.ipro.filtergrid.filter.LogicalOperator operator, List<Object> children) { }

    private static final class RawTree {
        final Object root;
        final List<RawCond> leaves = new ArrayList<>();

        RawTree(Object root) {
            this.root = root;
        }
    }

    private FilterNode parseWhere(String whereBody, Map<String, QueryBuilderMetadataCatalog.Entity> aliases,
                                  VisualQueryDefinition saved) {
        if (whereBody == null || whereBody.isBlank()) return null;
        JpqlLexer.Token[] tokens = JpqlLexer.tokenize(whereBody).toArray(JpqlLexer.Token[]::new);
        int[] cursor = new int[] { 0 };
        Object raw = parseWhereExpression(whereBody, tokens, cursor);
        if (raw == null || cursor[0] < tokens.length) {
            warnings.add("WHERE разобран не полностью — условия после позиции "
                    + (cursor[0] >= tokens.length ? "конца" : "«" + tokens[cursor[0]].value() + "»") + " пропущены.");
        }
        if (raw == null) {
            warnings.add("Условия WHERE не разобраны.");
            return null;
        }
        Map<String, SavedRef> values = savedWhereValues(saved);
        FilterNode node = materialize(raw, aliases, values);
        return node instanceof FilterGroup group && group.children().isEmpty() ? null : node;
    }

    private Object parseWhereExpression(String whereBody, JpqlLexer.Token[] tokens, int[] cursor) {
        List<Object> items = new ArrayList<>();
        List<org.ipro.filtergrid.filter.LogicalOperator> connectors = new ArrayList<>();
        Object first = parseWhereFactor(whereBody, tokens, cursor);
        if (first == null) return null;
        items.add(first);
        while (cursor[0] < tokens.length) {
            org.ipro.filtergrid.filter.LogicalOperator connector = null;
            if (tokens[cursor[0]].word("and")) connector = org.ipro.filtergrid.filter.LogicalOperator.AND;
            else if (tokens[cursor[0]].word("or")) connector = org.ipro.filtergrid.filter.LogicalOperator.OR;
            if (connector == null) break;
            cursor[0]++;
            Object next = parseWhereFactor(whereBody, tokens, cursor);
            if (next == null) return null;
            items.add(next);
            connectors.add(connector);
        }
        if (items.size() == 1) return items.get(0);
        if (connectors.stream().distinct().count() > 1) {
            // AND имеет приоритет: OR-группы из AND-групп.
            return orGroupsOf(items, connectors);
        }
        return new RawGroup(connectors.get(0), items);
    }

    private Object parseWhereFactor(String whereBody, JpqlLexer.Token[] tokens, int[] cursor) {
        if (cursor[0] < tokens.length && tokens[cursor[0]].type() == JpqlLexer.Type.LPAREN) {
            cursor[0]++;
            Object inner = parseWhereExpression(whereBody, tokens, cursor);
            if (inner == null || cursor[0] >= tokens.length
                    || tokens[cursor[0]].type() != JpqlLexer.Type.RPAREN) return null;
            cursor[0]++;
            // Скобки важны для round-trip: одиночное условие в скобках — группа из одного
            // (компилятор обёртывает каждую группу в скобки, голое условие — без скобок).
            if (inner instanceof RawCond) {
                return new RawGroup(org.ipro.filtergrid.filter.LogicalOperator.AND, List.of(inner));
            }
            return inner;
        }
        if (cursor[0] < tokens.length && tokens[cursor[0]].word("not")) {
            warnings.add("Отрицание NOT не восстанавливается — условие пропущено.");
            skipToGroupEnd(tokens, cursor);
            return new RawGroup(org.ipro.filtergrid.filter.LogicalOperator.AND, List.of());
        }
        // EXISTS(подзапрос): у условия нет левого поля — путь-заглушка @exists,
        // оператор-заглушка EQ (реальный рендеринг «exists (…)» выполняет компилятор).
        if (cursor[0] < tokens.length && tokens[cursor[0]].word("exists")) {
            cursor[0]++;
            String marker = subqueryAfterParen(whereBody, tokens, cursor);
            if (marker != null) {
                return new RawCond(VisualQueryDefinition.SUBQUERY_EXISTS_PATH,
                        FilterOperator.EQ, marker, null, null, null, false);
            }
            warnings.add("EXISTS без подзапроса select не поддерживается — условие пропущено.");
            skipConditionTail(tokens, cursor);
            return new RawGroup(org.ipro.filtergrid.filter.LogicalOperator.AND, List.of());
        }
        return parseWhereCondition(whereBody, tokens, cursor);
    }

    /** Если после курсора идёт «( select …)» — разбирает подзапрос и двигает курсор за скобку. */
    private String subqueryAfterParen(String whereBody, JpqlLexer.Token[] tokens, int[] cursor) {
        if (cursor[0] + 1 >= tokens.length || tokens[cursor[0]].type() != JpqlLexer.Type.LPAREN
                || !tokens[cursor[0] + 1].word("select")) return null;
        int open = cursor[0];
        int close = subqueryEnd(tokens, open);
        String marker = parseSubquery(whereBody, tokens, open, close);
        if (marker != null) cursor[0] = close + 1;
        return marker;
    }

    /** Индекс закрывающей скобки для '(' на openIndex (с учётом вложенности); -1, если не найдена. */
    private static int subqueryEnd(JpqlLexer.Token[] tokens, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < tokens.length; i++) {
            if (tokens[i].type() == JpqlLexer.Type.LPAREN) depth++;
            else if (tokens[i].type() == JpqlLexer.Type.RPAREN) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /**
     * Разбирает тело подзапроса (текст от select до закрывающей скобки) вложенным
n     * парсером и регистрирует его под именем subN; возвращает маркер-значение.
     */
    private String parseSubquery(String whereBody, JpqlLexer.Token[] tokens, int openIndex, int closeIndex) {
        if (closeIndex < 0 || openIndex + 1 >= closeIndex) return null;
        String body = whereBody.substring(tokens[openIndex + 1].position(), tokens[closeIndex].position()).trim();
        VisualQueryTextParser nested = new VisualQueryTextParser(catalog);
        nested.virtualCatalog = virtualCatalog;
        Parsed parsed = nested.parse(body, null);
        warnings.addAll(parsed.warnings());
        if (parsed.definition() == null) {
            warnings.add("Подзапрос не разобран — условие пропущено.");
            return null;
        }
        String name = "sub" + (subqueriesAccumulator.size() + 1);
        subqueriesAccumulator.add(new VisualQueryDefinition.Subquery(name, parsed.definition()));
        return VisualQueryDefinition.SUBQUERY_MARKER + name;
    }

    private Object parseWhereCondition(String whereBody, JpqlLexer.Token[] tokens, int[] cursor) {
        if (cursor[0] >= tokens.length || tokens[cursor[0]].type() != JpqlLexer.Type.WORD) return null;
        String path = tokens[cursor[0]].value();
        cursor[0]++;
        if (cursor[0] >= tokens.length) return null;
        JpqlLexer.Token op = tokens[cursor[0]];
        cursor[0]++;
        if (op.word("like")) {
            String value = parseValueToken(tokens, cursor, false);
            if (value == null) return null;
            if (value.startsWith(":")) {
                return new RawCond(path, FilterOperator.CONTAINS, null, null, value.substring(1), null, true);
            }
            // Литерал-шаблон: %x% → «Содержит», x% → «Начинается с», иначе «Содержит».
            if (value.length() > 1 && value.startsWith("%") && value.endsWith("%")) {
                return new RawCond(path, FilterOperator.CONTAINS,
                        value.substring(1, value.length() - 1), null, null, null, false);
            }
            if (value.length() > 1 && value.endsWith("%")) {
                return new RawCond(path, FilterOperator.STARTS_WITH,
                        value.substring(0, value.length() - 1), null, null, null, false);
            }
            warnings.add("LIKE-условие «" + path + "» без шаблона % — восстановлено как «Содержит».");
            return new RawCond(path, FilterOperator.CONTAINS, value, null, null, null, false);
        }
        if (op.word("is")) {
            boolean not = cursor[0] < tokens.length && tokens[cursor[0]].word("not");
            if (not) cursor[0]++;
            if (cursor[0] < tokens.length && tokens[cursor[0]].word("null")) {
                cursor[0]++;
                return new RawCond(path, not ? FilterOperator.IS_NOT_NULL : FilterOperator.IS_NULL,
                        null, null, null, null, false);
            }
            return null;
        }
        if (op.word("not")) {
            warnings.add("Отрицание NOT не восстанавливается — условие «" + path + "» пропущено.");
            skipConditionTail(tokens, cursor);
            return new RawGroup(org.ipro.filtergrid.filter.LogicalOperator.AND, List.of());
        }
        FilterOperator operator = comparisonOf(op);
        if (operator == null) {
            warnings.add("Оператор условия «" + op.value() + "» у поля " + path + " не поддерживается — условие пропущено.");
            skipConditionTail(tokens, cursor);
            return new RawGroup(org.ipro.filtergrid.filter.LogicalOperator.AND, List.of());
        }
        if (operator == FilterOperator.IN) {
            // Подзапрос: field in (select …)
            String subqueryMarker = subqueryAfterParen(whereBody, tokens, cursor);
            if (subqueryMarker != null) {
                return new RawCond(path, FilterOperator.IN, subqueryMarker, null, null, null, false);
            }
            String value = parseValueToken(tokens, cursor, true);
            return value == null ? null
                    : new RawCond(path, FilterOperator.IN, value, null, null, null, false);
        }
        if (operator == FilterOperator.BETWEEN) {
            String from = parseValueToken(tokens, cursor, false);
            if (from == null || cursor[0] >= tokens.length || !tokens[cursor[0]].word("and")) return null;
            cursor[0]++;
            String to = parseValueToken(tokens, cursor, false);
            if (to == null) return null;
            String fromParam = from.startsWith(":") ? from.substring(1) : null;
            String toParam = to.startsWith(":") ? to.substring(1) : null;
            return new RawCond(path, FilterOperator.BETWEEN,
                    fromParam == null ? from : null, toParam == null ? to : null,
                    fromParam, toParam, false);
        }
        // Скалярное сравнение с подзапросом: field <op> (select …)
        String subqueryMarker = subqueryAfterParen(whereBody, tokens, cursor);
        if (subqueryMarker != null) {
            return new RawCond(path, operator, subqueryMarker, null, null, null, false);
        }
        String value = parseValueToken(tokens, cursor, false);
        if (value == null) return null;
        return value.startsWith(":")
                ? new RawCond(path, operator, null, null, value.substring(1), null, false)
                : new RawCond(path, operator, value, null, null, null, false);
    }

    private static FilterOperator comparisonOf(JpqlLexer.Token token) {
        if (token.type() == JpqlLexer.Type.OP) {
            return switch (token.value()) {
                case "=" -> FilterOperator.EQ;
                case "<>", "!=" -> FilterOperator.NE;
                case ">" -> FilterOperator.GT;
                case ">=" -> FilterOperator.GE;
                case "<" -> FilterOperator.LT;
                case "<=" -> FilterOperator.LE;
                default -> null;
            };
        }
        if (token.type() == JpqlLexer.Type.WORD) {
            String word = token.value().toLowerCase(Locale.ROOT);
            return switch (word) {
                case "in" -> FilterOperator.IN;
                case "between" -> FilterOperator.BETWEEN;
                default -> null;
            };
        }
        return null;
    }

    /** Значение условия: строка, число или :параметр; для IN — список через запятую. */
    private String parseValueToken(JpqlLexer.Token[] tokens, int[] cursor, boolean list) {
        if (cursor[0] >= tokens.length) return null;
        JpqlLexer.Token token = tokens[cursor[0]];
        cursor[0]++;
        if (token.type() == JpqlLexer.Type.STRING) return token.value();
        if (token.type() == JpqlLexer.Type.NUMBER) return token.value();
        if (token.type() == JpqlLexer.Type.WORD && token.value().startsWith(":")) {
            return token.value();
        }
        if (list && token.type() == JpqlLexer.Type.LPAREN) {
            StringBuilder value = new StringBuilder();
            while (cursor[0] < tokens.length && tokens[cursor[0]].type() != JpqlLexer.Type.RPAREN) {
                JpqlLexer.Token item = tokens[cursor[0]];
                if (item.type() == JpqlLexer.Type.COMMA) value.append(",");
                else if (item.type() == JpqlLexer.Type.STRING || item.type() == JpqlLexer.Type.NUMBER) {
                    if (value.length() > 0 && !value.toString().endsWith(",")) value.append(",");
                    value.append(item.value());
                } else return null;
                cursor[0]++;
            }
            if (cursor[0] >= tokens.length) return null;
            cursor[0]++;
            return value.toString();
        }
        return null;
    }

    private static void skipToGroupEnd(JpqlLexer.Token[] tokens, int[] cursor) {
        int depth = 0;
        while (cursor[0] < tokens.length) {
            JpqlLexer.Token token = tokens[cursor[0]];
            if (token.type() == JpqlLexer.Type.LPAREN) depth++;
            if (token.type() == JpqlLexer.Type.RPAREN) {
                if (depth == 0) return;
                depth--;
            }
            if (depth == 0 && (token.word("and") || token.word("or"))) return;
            cursor[0]++;
        }
    }

    private static void skipConditionTail(JpqlLexer.Token[] tokens, int[] cursor) {
        while (cursor[0] < tokens.length) {
            JpqlLexer.Token token = tokens[cursor[0]];
            if (token.word("and") || token.word("or")) return;
            if (token.type() == JpqlLexer.Type.RPAREN) return;
            cursor[0]++;
        }
    }

    /** ИЛИ-группы из И-групп (приоритет AND выше OR). */
    private Object orGroupsOf(List<Object> items, List<org.ipro.filtergrid.filter.LogicalOperator> connectors) {
        List<Object> orItems = new ArrayList<>();
        List<Object> andItems = new ArrayList<>();
        andItems.add(items.get(0));
        for (int i = 0; i < connectors.size(); i++) {
            if (connectors.get(i) == org.ipro.filtergrid.filter.LogicalOperator.AND) {
                andItems.add(items.get(i + 1));
            } else {
                orItems.add(andItems.size() == 1 ? andItems.get(0) : new RawGroup(org.ipro.filtergrid.filter.LogicalOperator.AND, andItems));
                andItems = new ArrayList<>();
                andItems.add(items.get(i + 1));
            }
        }
        orItems.add(andItems.size() == 1 ? andItems.get(0) : new RawGroup(org.ipro.filtergrid.filter.LogicalOperator.AND, andItems));
        return orItems.size() == 1 ? orItems.get(0) : new RawGroup(org.ipro.filtergrid.filter.LogicalOperator.OR, orItems);
    }

    /** Сырые узлы → FilterNode: типы полей по каталогу, значения :visualFilter_* — из сохранённого черновика. */
    private FilterNode materialize(Object raw, Map<String, QueryBuilderMetadataCatalog.Entity> aliases,
                                   Map<String, SavedRef> savedValues) {
        if (raw instanceof RawCond cond) {
            FilterOperator operator = cond.operator();
            String value = cond.value();
            String valueTo = cond.valueTo();
            if (cond.paramName() != null) {
                SavedRef ref = savedValues.get(cond.paramName());
                if (ref != null && ref.condition().path().equals(cond.path()) && ref.valueSlot()) {
                    // LIKE скрывает CONTAINS/STARTS_WITH: оператор берём из сохранённого условия.
                    if (cond.like() && (ref.condition().operator() == FilterOperator.CONTAINS
                            || ref.condition().operator() == FilterOperator.STARTS_WITH)) {
                        operator = ref.condition().operator();
                    }
                    value = ref.condition().value();
                } else {
                    warnings.add("Значение условия «" + cond.path() + "» (параметр :" + cond.paramName()
                            + ") не восстановлено — заполните его на вкладке «Условия».");
                    value = "";
                }
            }
            if (cond.paramNameTo() != null) {
                SavedRef ref = savedValues.get(cond.paramNameTo());
                if (ref != null && ref.condition().path().equals(cond.path()) && !ref.valueSlot()) {
                    valueTo = ref.condition().valueTo();
                } else {
                    warnings.add("Второе значение условия «" + cond.path() + "» (:" + cond.paramNameTo()
                            + ") не восстановлено — заполните его на вкладке «Условия».");
                    valueTo = "";
                }
            }
            FilterDataType dataType;
            if (VisualQueryDefinition.SUBQUERY_EXISTS_PATH.equals(cond.path())) {
                // Условие EXISTS(подзапрос): левого поля нет, тип условный.
                dataType = FilterDataType.TEXT;
            } else {
                dataType = conditionDataType(cond.path(), aliases);
                if (dataType == null) {
                    warnings.add("Поле условия «" + cond.path() + "» не найдено в каталоге — условие пропущено.");
                    return new FilterGroup(org.ipro.filtergrid.filter.LogicalOperator.AND, List.of());
                }
            }
            // value для CONTAINS/STARTS_WITH — сырой текст без %: компилятор сам обёртывает.
            return new FilterConditionNode(new FilterCondition(cond.path(), operator, value, valueTo, dataType));
        }
        RawGroup group = (RawGroup) raw;
        List<FilterNode> children = new ArrayList<>();
        for (Object child : group.children()) {
            FilterNode node = materialize(child, aliases, savedValues);
            if (node instanceof FilterGroup empty && empty.children().isEmpty()) continue;
            children.add(node);
        }
        if (children.isEmpty()) return new FilterGroup(group.operator(), List.of());
        return new FilterGroup(group.operator(), children);
    }

    /** Тип условия по полю каталога; null, если поле не найдено. */
    private FilterDataType conditionDataType(String path, Map<String, QueryBuilderMetadataCatalog.Entity> aliases) {
        int dot = path.indexOf('.');
        if (dot <= 0) return null;
        QueryBuilderMetadataCatalog.Entity entity = aliases.get(path.substring(0, dot));
        if (entity == null) return null;
        String field = path.substring(dot + 1);
        QueryBuilderMetadataCatalog.Field plain = fieldOf(entity, field);
        if (plain != null) return dataTypeOf(plain.javaType());
        // Первичный ключ не входит в поля формы, но валиден в условиях (s.id).
        if ("id".equals(field)) return FilterDataType.NUMBER;
        return null; // ассоциации в WHERE не используются (нужен typed lookup)
    }

    private static FilterDataType dataTypeOf(Class<?> type) {
        if (type.isEnum()) return FilterDataType.ENUM;
        if (Number.class.isAssignableFrom(type) || type.isPrimitive()) return FilterDataType.NUMBER;
        if (type == Boolean.class || type == boolean.class) return FilterDataType.BOOLEAN;
        if (java.time.temporal.Temporal.class.isAssignableFrom(type)) return FilterDataType.DATE;
        return FilterDataType.TEXT;
    }

    /**
     * Восстановление значений из сохранённого черновика: имена visualFilter_N
     * воспроизводят нумерацию компилятора (BETWEEN занимает два параметра,
     * условия-параметры :имя — ни одного).
     */
    private Map<String, SavedRef> savedWhereValues(VisualQueryDefinition saved) {
        Map<String, SavedRef> result = new HashMap<>();
        if (saved == null || saved.where() == null) return result;
        int next = 1;
        for (org.ipro.filtergrid.filter.FilterConditionNode leaf : leavesOf(saved.where())) {
            org.ipro.filtergrid.filter.FilterCondition condition = leaf.condition();
            int consumed = paramsConsumed(condition);
            if (consumed >= 1) result.put("visualFilter_" + next++, new SavedRef(condition, true));
            if (consumed >= 2) result.put("visualFilter_" + next++, new SavedRef(condition, false));
        }
        return result;
    }

    /** Сколько :visualFilter_* порождает условие при компиляции (логика ProjectionFilterCompiler). */
    private static int paramsConsumed(org.ipro.filtergrid.filter.FilterCondition condition) {
        // Подзапрос-условие не порождает параметров (SQL подзапроса вставляется как есть).
        if (VisualQueryDefinition.SUBQUERY_EXISTS_PATH.equals(condition.path())) return 0;
        if (condition.value() != null && condition.value().startsWith(VisualQueryDefinition.SUBQUERY_MARKER)) return 0;
        if (FilterCondition.requiresNoValue(condition.operator())) return 0;
        boolean valueParam = FilterParameterRef.parse(condition.value()) != null;
        if (condition.operator() == FilterOperator.BETWEEN) {
            boolean toParam = FilterParameterRef.parse(condition.valueTo()) != null;
            return (valueParam ? 0 : 1) + (toParam ? 0 : 1);
        }
        return valueParam ? 0 : 1;
    }

    private static List<org.ipro.filtergrid.filter.FilterConditionNode> leavesOf(FilterNode node) {
        List<org.ipro.filtergrid.filter.FilterConditionNode> result = new ArrayList<>();
        collectLeaves(node, result);
        return result;
    }

    private static void collectLeaves(FilterNode node, List<org.ipro.filtergrid.filter.FilterConditionNode> result) {
        if (node instanceof org.ipro.filtergrid.filter.FilterConditionNode leaf) {
            result.add(leaf);
            return;
        }
        for (FilterNode child : ((FilterGroup) node).children()) {
            collectLeaves(child, result);
        }
    }

    // === GROUP BY / HAVING / ORDER BY ===

    private List<String> parseGroupBy(String body, Map<String, QueryBuilderMetadataCatalog.Entity> aliases,
                                      List<VisualQueryDefinition.SelectField> selects,
                                      List<VisualQueryDefinition.Aggregate> aggregates) {
        List<String> result = new ArrayList<>();
        if (body == null || body.isBlank()) return result;
        for (String item : splitTopLevel(body, ',')) {
            String path = item.trim();
            if (selectableDeepPath(path, aliases)) {
                result.add(path);
                continue;
            }
            // Алиас SELECT-колонки → её путь.
            VisualQueryDefinition.SelectField byAlias = selects.stream()
                    .filter(field -> field.resultName().equals(path)).findFirst().orElse(null);
            if (byAlias != null && selectableDeepPath(byAlias.path(), aliases)) {
                result.add(byAlias.path());
                continue;
            }
            VisualQueryDefinition.Aggregate byAggregate = aggregates.stream()
                    .filter(aggregate -> aggregate.resultName().equals(path)).findFirst().orElse(null);
            if (byAggregate != null) {
                warnings.add("Агрегат «" + path + "» в GROUP BY пропущен — агрегаты в группировку не входят.");
                continue;
            }
            warnings.add("Поле «" + path + "» в GROUP BY не найдено — пропущено.");
        }
        return result;
    }

    private VisualQueryDefinition.Having parseHaving(String body, List<VisualQueryDefinition.Aggregate> aggregates) {
        if (body == null || body.isBlank()) return null;
        JpqlLexer.Token[] tokens = JpqlLexer.tokenize(body).toArray(JpqlLexer.Token[]::new);
        int[] cursor = new int[] { 0 };
        List<VisualQueryDefinition.HavingCondition> conditions = new ArrayList<>();
        List<VisualQueryDefinition.HavingOperator> operators = new ArrayList<>();
        List<JoinLogicalOperator> connectors = new ArrayList<>();
        while (cursor[0] < tokens.length) {
            if (tokens[cursor[0]].type() == JpqlLexer.Type.LPAREN) cursor[0]++;
            VisualQueryDefinition.HavingCondition condition = parseHavingCondition(tokens, cursor, aggregates);
            if (condition == null) {
                warnings.add("Условие HAVING не разобрано — пропущено.");
                break;
            }
            conditions.add(condition);
            if (cursor[0] < tokens.length && tokens[cursor[0]].type() == JpqlLexer.Type.RPAREN) cursor[0]++;
            if (cursor[0] >= tokens.length) break;
            JoinLogicalOperator connector = tokens[cursor[0]].word("or") ? JoinLogicalOperator.OR
                    : tokens[cursor[0]].word("and") ? JoinLogicalOperator.AND : null;
            if (connector == null) break;
            connectors.add(connector);
            cursor[0]++;
        }
        if (conditions.isEmpty()) {
            warnings.add("Условия HAVING не разобраны.");
            return null;
        }
        JoinLogicalOperator operator = connectors.isEmpty() ? JoinLogicalOperator.AND : connectors.get(0);
        if (connectors.stream().distinct().count() > 1) {
            warnings.add("Смешанные И/ИЛИ в HAVING — восстановлено с оператором «"
                    + (operator == JoinLogicalOperator.AND ? "И" : "ИЛИ") + "».");
        }
        return new VisualQueryDefinition.Having(operator, conditions);
    }

    private VisualQueryDefinition.HavingCondition parseHavingCondition(JpqlLexer.Token[] tokens, int[] cursor,
                                                                       List<VisualQueryDefinition.Aggregate> aggregates) {
        if (cursor[0] + 2 >= tokens.length) return null;
        JpqlLexer.Token alias = tokens[cursor[0]];
        JpqlLexer.Token op = tokens[cursor[0] + 1];
        JpqlLexer.Token value = tokens[cursor[0] + 2];
        if (alias.type() != JpqlLexer.Type.WORD || op.type() != JpqlLexer.Type.OP) return null;
        VisualQueryDefinition.HavingOperator operator = comparisonOf(op) == null ? null
                : havingOperatorOf(comparisonOf(op));
        if (operator == null) {
            warnings.add("Оператор HAVING «" + op.value() + "» не поддерживается.");
            return null;
        }
        VisualQueryDefinition.HavingValue havingValue = havingValueOf(value, aggregates);
        if (havingValue == null) {
            warnings.add("Значение HAVING «" + value.value() + "» не разобрано.");
            return null;
        }
        cursor[0] += 3;
        return new VisualQueryDefinition.HavingCondition(alias.value(), operator, havingValue);
    }

    private static VisualQueryDefinition.HavingOperator havingOperatorOf(FilterOperator operator) {
        return switch (operator) {
            case EQ -> VisualQueryDefinition.HavingOperator.EQ;
            case NE -> VisualQueryDefinition.HavingOperator.NE;
            case GT -> VisualQueryDefinition.HavingOperator.GT;
            case GE -> VisualQueryDefinition.HavingOperator.GE;
            case LT -> VisualQueryDefinition.HavingOperator.LT;
            case LE -> VisualQueryDefinition.HavingOperator.LE;
            default -> null;
        };
    }

    private VisualQueryDefinition.HavingValue havingValueOf(JpqlLexer.Token token,
                                                            List<VisualQueryDefinition.Aggregate> aggregates) {
        if (token.type() == JpqlLexer.Type.NUMBER) {
            return new VisualQueryDefinition.HavingNumber(Double.parseDouble(token.value().replace(',', '.')));
        }
        if (token.type() == JpqlLexer.Type.WORD && token.value().startsWith(":")) {
            String name = token.value().substring(1);
            if (name.matches("[A-Za-z_][A-Za-z0-9_]*")) return new VisualQueryDefinition.HavingParamRef(name);
            return null;
        }
        if (token.type() == JpqlLexer.Type.WORD && aggregates.stream()
                .anyMatch(aggregate -> aggregate.resultName().equals(token.value()))) {
            return new VisualQueryDefinition.HavingAggregateRef(token.value());
        }
        return null;
    }

    private List<VisualQueryOrder> parseOrderBy(String body, Map<String, QueryBuilderMetadataCatalog.Entity> aliases,
                                                List<VisualQueryDefinition.SelectField> selects) {
        List<VisualQueryOrder> result = new ArrayList<>();
        if (body == null || body.isBlank()) return result;
        for (String item : splitTopLevel(body, ',')) {
            JpqlLexer.Token[] tokens = JpqlLexer.tokenize(item.trim()).toArray(JpqlLexer.Token[]::new);
            if (tokens.length < 1 || tokens[0].type() != JpqlLexer.Type.WORD) continue;
            String path = tokens[0].value();
            VisualQueryOrder.Direction direction = VisualQueryOrder.Direction.ASC;
            if (tokens.length > 1 && tokens[1].word("desc")) direction = VisualQueryOrder.Direction.DESC;
            if (selectableDeepPath(path, aliases)) {
                result.add(new VisualQueryOrder(path, direction));
                continue;
            }
            VisualQueryDefinition.SelectField byAlias = selects.stream()
                    .filter(field -> field.resultName().equals(path)).findFirst().orElse(null);
            if (byAlias != null && selectableDeepPath(byAlias.path(), aliases)) {
                result.add(new VisualQueryOrder(byAlias.path(), direction));
                continue;
            }
            warnings.add("Поле «" + path + "» в ORDER BY не найдено — пропущено.");
        }
        return result;
    }

    // === Утилиты каталога и aliases ===

    private QueryBuilderMetadataCatalog.Entity entityOf(String name) {
        QueryBuilderMetadataCatalog.Entity virtual = virtualCatalog.entity(name);
        if (virtual != null) return virtual;
        QueryBuilderMetadataCatalog.Entity entity = safeRoot(name);
        if (entity != null) return entity;
        int dot = name.lastIndexOf('.');
        return dot < 0 ? null : safeRoot(name.substring(dot + 1));
    }

    private QueryBuilderMetadataCatalog.Entity safeRoot(String name) {
        try {
            return catalog.root(name);
        } catch (RuntimeException unknown) {
            return null;
        }
    }

    private QueryBuilderMetadataCatalog.Entity entityOfJoin(VisualQueryDefinition.Join join,
                                                            Map<String, QueryBuilderMetadataCatalog.Entity> aliases) {
        if (join.independent()) return entityOf(join.sourcePath());
        String parent = join.parentAlias();
        QueryBuilderMetadataCatalog.Entity source = aliases.get(parent);
        var association = source == null ? null : associationOf(source, join.sourcePath());
        if (association == null || association.targetType() == null) return null;
        return entityByType(association.targetType());
    }

    private QueryBuilderMetadataCatalog.Entity entityByType(Class<?> type) {
        for (var entity : catalog.roots()) {
            if (entity.javaType().equals(type)) return entity;
        }
        return null;
    }

    private static QueryBuilderMetadataCatalog.Field fieldOf(QueryBuilderMetadataCatalog.Entity entity, String name) {
        return entity == null ? null : entity.fields().stream()
                .filter(field -> field.name().equals(name)).findFirst().orElse(null);
    }

    private static QueryBuilderMetadataCatalog.Association associationOf(QueryBuilderMetadataCatalog.Entity entity,
                                                                         String name) {
        return entity == null ? null : entity.associations().stream()
                .filter(association -> association.name().equals(name)).findFirst().orElse(null);
    }

    private boolean selectableByAlias(String path, Map<String, QueryBuilderMetadataCatalog.Entity> aliases) {
        int dot = path.indexOf('.');
        if (dot <= 0) return false;
        QueryBuilderMetadataCatalog.Entity entity = aliases.get(path.substring(0, dot));
        String field = path.substring(dot + 1);
        return selectable(entity, field);
    }

    private List<VisualQueryDefinition.Join> joinsOf(Map<String, QueryBuilderMetadataCatalog.Entity> aliases) {
        return List.copyOf(joinsAccumulator);
    }

    private static boolean hasRoot(Map<String, QueryBuilderMetadataCatalog.Entity> aliases) {
        return !aliases.isEmpty();
    }

    private static String rootAliasOf(Map<String, QueryBuilderMetadataCatalog.Entity> aliases) {
        return aliases.keySet().iterator().next();
    }

    /** Разбиение по запятым верхнего уровня (вне кавычек и скобок). */
    private static List<String> splitTopLevel(String body, char separator) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inQuote = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
                current.append(c);
                continue;
            }
            if (!inQuote) {
                if (c == '(') depth++;
                if (c == ')') depth--;
                if (c == separator && depth == 0) {
                    result.add(current.toString());
                    current.setLength(0);
                    continue;
                }
            }
            current.append(c);
        }
        result.add(current.toString());
        return result;
    }
}
