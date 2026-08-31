package org.ipro.reportstudio.query;

import org.ipro.filter.FilterCondition;
import org.ipro.filter.FilterConditionNode;
import org.ipro.filter.FilterDataType;
import org.ipro.filter.FilterGroup;
import org.ipro.filter.FilterNode;
import org.ipro.filter.FilterOperator;
import org.ipro.filter.LogicalOperator;
import org.ipro.reportstudio.query.VisualQueryDefinition.Aggregate;
import org.ipro.reportstudio.query.VisualQueryDefinition.SelectField;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Детерминированный парсер «фразы на русском/английском» в визуальный запрос
 * ({@link VisualQueryDefinition}). Никакого NLP: фраза режется по структурным
 * якорям (глаголам клауз) на SELECT / FROM / GROUP BY / итоги / ORDER BY, каждое
 * имя сопоставляется с полями корневой сущности каталога ({@link
 * QueryBuilderMetadataCatalog}).
 *
 * <p>Пример фразы (упрощённая):</p>
 * <pre>
 *   Получи журнал, наименование и код спецификации из спецификации
 *   и сгруппируй по журналу и сделай итоги по номенклатура
 * </pre>
 *
 * <p>Результат — <em>предложение</em>, а не факт: при первом же нераспознанном
 * слове или неоднозначности парсер не выбрасывает ошибку, а пишет предупреждение
 * и продолжает. Вдобавок возвращается <em>оценка уверенности</em> (0..1) по доле
 * распознанных элементов, чтобы вызывающий мог потребовать подтверждения при
 * низкой уверенности. Готовый запрос всегда можно подтвердить/поправить в
 * визуальном конструкторе — построитель служит экраном контроля.</p>
 *
 * <p>Поля можно указывать и <em>сквозь связи</em>: точечная нотация
 * «категория.наименование» либо естественная «наименование категории» — парсер
 * порождает нужные JOIN по ассоциациям сущности. «Сделай итоги по …» умеет
 * выбирать функцию-агрегат по числовому полю (SUM/AVG/MIN/MAX/COUNT).</p>
 *
 * <p>Распознанными считаются глаголы выборки («получи/выбери/выведи/покажи/
 * отобрази/возьми/select»), источник — частица «из», агрегация («сгруппируй/…»,
 * «сделай группировку»), порядок («отсортируй/…» + «по возрастанию/убыванию»),
 * итоги («сделай/подведи итоги по», а также слова суммы/среднего/максимума/минимума).
 * Синонимы «фраза → сущность/поле» задаются словарём, иначе имя сопоставляется с
 * подписями каталога.</p>
 */
public final class NaturalLanguageQueryParser {

    /** Результат разбора: определение (null, если собрать оказалось не из чего),
     *  предупреждения и оценка уверенности 0..1. */
    public record Result(VisualQueryDefinition definition, List<String> warnings, double confidence) { }

    /** Unicode-границы слов: по умолчанию {@code \b} в Java понимает только ASCII.
     *  Запятая/точка с запятой/двоеточие и пробельное тире (« - », « – », « — »)
     *  — признаки перехода между сегментами (полями списка или последующей клаузой). */
    private static final Pattern ITEM_SPLIT =
            Pattern.compile("(?U)\\s*(?:[,;:]|\\h+[\u2013\u2014-]\\h+|\\bи\\b)\\s*");
    private static final Pattern LEADING_ARTICLE =
            Pattern.compile("^\\s*(?:по|в)\\s+");
    private static final Pattern ASC_WORD = Pattern.compile("(?i)\\basc\\b");
    private static final Pattern DESC_WORD = Pattern.compile("(?i)\\bdesc\\b");

    /** Служебные глаголы выборки — срезается только самым первым в SELECT-части. */
    private static final Pattern SELECT_VERB = Pattern.compile(
            "(?i)(?U)^(?:получите|получить|получи|выберите|выбрать|выбери|выбирай|выведи|вывести|"
            + "покажи|отобрази|отобразить|возьми|взять|выдай|select)\\b\\s*");

    /** Тот же глагол в середине текста (после «из») — признак начала полей выборки. */
    private static final Pattern SELECT_VERB_MID = Pattern.compile(
            "(?i)(?U)\\b(?:получите|получить|получи|выберите|выбрать|выбери|выбирай|выведи|вывести|"
            + "покажи|отобрази|отобразить|возьми|взять|выдай|select)\\b");

    // Клаузы-«якоря». Каждый паттерн — только ядро глагола (без служебного «по»);
    // артикль «по» срезается в теле клаузы. Слова в lowercase.
    private static final List<String> CLAUSE_MARKERS = List.of(
            // ПОРЯДОК
            "отсортируйте", "отсортировать", "сортируете", "отсортируй",
            "сортируй", "сортировать", "сделай сортировку", "сделать сортировку",
            "упорядочить", "упорядочь",
            // ГРУППИРОВКА
            "сгруппируйте", "сгруппировать", "сгруппируй",
            "группировать", "группируй", "сделай группировку", "сделать группировку",
            "создай группировку", "создать группировку",
            // ИТОГИ
            "сделайте итоги", "сделать итоги", "сделай итоги",
            "подвели итоги", "подведи итоги", "итоги",
            // УСЛОВИЯ (WHERE)
            "где", "у которых", "у которых есть", "которые", "соответствующие",
            "при условии", "сёлфильтр");

    private enum Kind { GROUP, ORDER, TOTALS, WHERE }

    private record Marker(Kind kind, int start, int end) { }

    private static final List<Pattern> MARKER_PATTERNS = buildMarkerPatterns();

    private final QueryBuilderMetadataCatalog catalog;
    private final Map<String, String> sourceAliases = new LinkedHashMap<>();
    private final Map<String, String> fieldAliases = new LinkedHashMap<>();

    // Состояние одного разбора (собирается прямо в определении): генерация JOIN,
    // счётчики для confidence. Сбрасываются в parse().
    private final List<VisualQueryDefinition.Join> joins = new ArrayList<>();
    private final Map<String, String> joinAliasByKey = new LinkedHashMap<>();
    private int tokensAttempted;
    private int tokensResolved;

    public NaturalLanguageQueryParser(QueryBuilderMetadataCatalog catalog) {
        this.catalog = catalog;
    }

    /** «Естественное» имя источника → имя сущности каталога (например «спецификация» → «Specification»). */
    public NaturalLanguageQueryParser addSourceAlias(String natural, String entityName) {
        sourceAliases.put(lower(natural.trim()), entityName);
        return this;
    }

    /** «Естественное» имя поля → имя Java-поля сущности (например «код спецификации» → «code»). */
    public NaturalLanguageQueryParser addFieldAlias(String natural, String fieldName) {
        fieldAliases.put(lower(normalize(natural)), fieldName);
        return this;
    }

    /** Синонимы «… → сущность» (перезаписывают заданные ранее по умолчанию). */
    public NaturalLanguageQueryParser withSourceAliases(Map<String, String> aliases) {
        sourceAliases.clear();
        sourceAliases.putAll(aliases);
        return this;
    }

    public Result parse(String phrase) {
        List<String> warnings = new ArrayList<>();
        resetState();
        if (phrase == null || phrase.isBlank()) {
            return new Result(null, List.of("Фраза пустая"), 0.0);
        }
        try {
            Result r = doParse(phrase.trim().replaceAll("\\s+", " "), warnings);
            return new Result(r.definition, r.warnings, clamp(r.confidence));
        } catch (RuntimeException broken) {
            String message = broken.getMessage();
            warnings.add("Не удалось разобрать фразу: " + (message == null || message.isBlank()
                    ? "неизвестная ошибка" : message));
            return new Result(null, warnings, 0.0);
        }
    }

    private void resetState() {
        joins.clear();
        joinAliasByKey.clear();
        tokensAttempted = 0;
        tokensResolved = 0;
    }

    // === Разбор ===

    private Result doParse(String text, List<String> warnings) {
        // 1. Источник: всё до первой клаузы после «из» — SELECT+FROM, дальше — клаузы.
        //    «Из» может стоять в начале фразы (с заглавной) — ищем без учёта регистра.
        Matcher iz = Pattern.compile("(?i)(?U)\\bиз\\b").matcher(text);
        String selectPart;
        String tail;
        boolean sourceEarly = false;
        if (iz.find()) {
            selectPart = text.substring(0, iz.start()).trim();
            tail = text.substring(iz.end()).trim();
        } else {
            warnings.add("Не найден источник («из …») — беру первую разрешённую каталогом сущность.");
            sourceEarly = true;
            selectPart = text.trim();
            tail = "";
        }

        // 1a. Вариант «из <источник> : <поля…>»: двоеточие/пробельное тире после имени
        //     источника или глагол выборки после него («из номенклатура Получи код»)
        //     отделяют поля выборки (и возможные клаузы) от самого источника.
        List<ClauseBody> preClauses = new ArrayList<>();
        String extraFields = "";
        int separator = topLevelSeparatorIndex(tail);
        int verbStart = firstSelectVerbStart(tail);
        int firstMarker = firstClauseMarkerStart(tail);
        boolean symbolBoundary = false;
        int boundary = -1;
        if (separator >= 0 && (firstMarker < 0 || separator < firstMarker)) {
            boundary = separator;
            symbolBoundary = true;
        }
        if (verbStart >= 0 && (firstMarker < 0 || verbStart < firstMarker)
                && (boundary < 0 || verbStart < boundary)) {
            boundary = verbStart;
            symbolBoundary = false;
        }
        if (boundary >= 0) {
            // Для символа-разделителя поля начинаются ПОСЛЕ него, для глагола — с него.
            String payload = tail.substring(symbolBoundary ? boundary + 1 : boundary).trim();
            tail = tail.substring(0, boundary).trim();
            TailSegments payloadSegments = segmentTail(payload);
            extraFields = payloadSegments.source();
            preClauses.addAll(payloadSegments.clauses());
        }

        // 2. Разбиваем «хвост» на источник + клаузы.
        TailSegments segments = segmentTail(tail);
        boolean clausesUnparsed = segments == null;
        if (clausesUnparsed) {
            warnings.add("Клаузы после «из» не распознаны: «" + tail + "».");
        }
        String sourceText = segments == null ? "" : segments.source();
        List<ClauseBody> clauses = new ArrayList<>(preClauses);
        clauses.addAll(segments == null ? List.of() : segments.clauses());

        // 3. Источник (FROM).
        QueryBuilderMetadataCatalog.Entity entity = resolveSource(sourceText, warnings);
        boolean sourceGuessed = false;
        if (entity == null) {
            if (catalog != null && !catalog.roots().isEmpty()) {
                entity = catalog.roots().get(0);
                sourceGuessed = true;
                warnings.add("Источник «" + sourceText + "» не найден в каталоге — беру первую "
                        + "разрешённую сущность («" + entity.entityName() + "»).");
            } else {
                warnings.add("Источник «" + sourceText + "» не найден в каталоге.");
                return new Result(null, warnings, 0.0);
            }
        }
        if (sourceEarly || sourceText.isBlank()) sourceGuessed = true;
        String alias = "e";

        // 4. Выборка (SELECT): до «из» + поля после разделителя (если были).
        List<SelectField> selects = new ArrayList<>();
        if (!selectPart.isBlank()) {
            resolveSelect(selectPart, entity, alias, selects, warnings);
        }
        if (!extraFields.isBlank()) {
            resolveSelect(extraFields, entity, alias, selects, warnings);
        }
        if (selects.isEmpty()) {
            warnings.add("Выборка (SELECT) пуста.");
        }

        // 5. Клаузы: группировка, порядок, итоги, условия (WHERE).
        List<String> groupBy = new ArrayList<>();
        List<Aggregate> aggregates = new ArrayList<>();
        List<VisualQueryOrder> orders = new ArrayList<>();
        List<org.ipro.filter.FilterNode> whereNodes = new ArrayList<>();
        boolean totals = false;
        for (ClauseBody clause : clauses) {
            switch (clause.kind()) {
                case GROUP -> resolveFields(clause.body(), entity, alias, groupBy, warnings);
                case ORDER -> resolveOrders(clause.body(), entity, alias, orders, warnings);
                case TOTALS -> totals |= resolveTotals(clause.body(), entity, alias, groupBy, aggregates, warnings);
                case WHERE -> {
                    org.ipro.filter.FilterNode node = resolveWhere(clause.body(), entity, alias, warnings);
                    if (node != null) whereNodes.add(node);
                }
            }
        }
        org.ipro.filter.FilterNode where = combineWhere(whereNodes);

        if (selects.isEmpty() && aggregates.isEmpty()) {
            // Источник известен — не выбрасываем его из-за сомнительных полей. Но
            // VisualQueryDefinition требует хотя бы одно поле выборки, поэтому, чтобы
            // источник всё-таки показался, подставляем первое неслужебное поле с явным предупреждением.
            QueryBuilderMetadataCatalog.Field fallback = entity.fields().stream()
                    .filter(f -> !f.technical())
                    .findFirst()
                    .orElse(entity.fields().isEmpty() ? null : entity.fields().get(0));
            if (fallback != null) {
                selects.add(new SelectField(alias + "." + fallback.name(), fallback.name()));
                warnings.add("Ни одно поле выборки/итогов не распознано — для отображения источника "
                        + "(«" + entity.entityName() + "») выбрано первое поле «" + fallback.caption() + "». "
                        + "Проверьте фразу.");
            } else {
                warnings.add("Не распознано ни одного поля выборки/итогов — запрос не собран "
                        + "(в источнике нет полей).");
                return new Result(null, warnings, 0.0);
            }
        }

        VisualQueryDefinition definition = new VisualQueryDefinition(
                VisualQueryDefinition.CURRENT_VERSION, entity.entityName(), alias,
                List.copyOf(selects), List.copyOf(joins), List.copyOf(groupBy),
                List.copyOf(aggregates), List.of(), null, where, List.of(), List.copyOf(orders));

        double confidence = confidence(sourceGuessed, sourceEarly, clausesUnparsed);

        return new Result(definition, List.copyOf(warnings), confidence);
    }

    private double confidence(boolean guessedSource, boolean sourceEarly, boolean clausesUnparsed) {
        double base = tokensAttempted == 0
                ? (guessedSource ? 0.3 : 0.5)
                : (double) tokensResolved / Math.max(1, tokensAttempted);
        if (sourceEarly) base *= 0.8;
        if (clausesUnparsed) base = Math.min(base, 0.5);
        return base;
    }

    // === Сегментация хвоста на источник и клаузы ===

    private record TailSegments(String source, List<ClauseBody> clauses) { }
    private record ClauseBody(Kind kind, String body) { }

    private TailSegments segmentTail(String tail) {
        if (tail.isBlank()) return new TailSegments("", List.of());
        String lower = lower(tail);

        // Источник — до первого якоря клаузы.
        int firstMarkerEnd = Integer.MAX_VALUE;
        Kind firstKind = null;
        for (int i = 0; i < CLAUSE_MARKERS.size(); i++) {
            Pattern p = MARKER_PATTERNS.get(i);
            Matcher m = p.matcher(lower);
            if (m.find() && m.start() < firstMarkerEnd) {
                firstMarkerEnd = m.start();
                firstKind = markerKind(CLAUSE_MARKERS.get(i));
            }
        }
        String sourceRaw = firstMarkerEnd == Integer.MAX_VALUE ? tail : tail.substring(0, firstMarkerEnd);
        String source = sourceRaw.replaceAll("(?i)\\s+и\\s*$", "")
                .replaceAll("\\s*[;,:]\\s*$", "")
                .replaceAll("\\s+[\u2013\u2014-]\\s*$", "")
                .trim();
        List<ClauseBody> clauses = new ArrayList<>();
        if (firstMarkerEnd != Integer.MAX_VALUE) {
            clauses = collectClauses(lower, firstMarkerEnd, tail.length());
        }
        return new TailSegments(source, clauses);
    }

    /**
     * Идём по якорям: тело каждой клаузы — от конца её маркера до начала следующего
     * (служебные «по»/«и» в теле срезаются при разборе конкретной клаузы).
     */
    private List<ClauseBody> collectClauses(String lower, int start, int length) {
        List<ClauseBody> clauses = new ArrayList<>();
        int pos = start;
        Marker cur = nextMarkerFrom(lower, pos);
        while (cur != null) {
            int bodyStart = cur.end();
            int bodyEnd = nextMarkerStartFrom(lower, bodyStart, length);
            clauses.add(new ClauseBody(cur.kind(), lower.substring(bodyStart, bodyEnd)));
            pos = bodyEnd;
            cur = (pos < length) ? nextMarkerFrom(lower, pos) : null;
        }
        return clauses;
    }

    /** Следующий якорь клаузы с позиции {@code from}; null, если его нет. */
    private Marker nextMarkerFrom(String lower, int from) {
        Marker best = null;
        for (int i = 0; i < MARKER_PATTERNS.size(); i++) {
            Matcher m = MARKER_PATTERNS.get(i).matcher(lower);
            if (m.find(from) && (best == null || m.start() < best.start())) {
                best = new Marker(markerKind(CLAUSE_MARKERS.get(i)), m.start(), m.end());
            }
        }
        return best;
    }

    /** Позиция начала следующего маркера после {@code from} (={@code length}, если нет). */
    private int nextMarkerStartFrom(String lower, int from, int length) {
        Marker next = nextMarkerFrom(lower, from);
        return next == null ? length : next.start();
    }

    /** Позиция первого разделителя «источник/поля» — двоеточие, точка с запятой или
     *  пробельное тире (вне кавычек); -1, если разделителя нет. */
    private static int topLevelSeparatorIndex(String text) {
        if (text == null || text.isBlank()) return -1;
        boolean inQuote = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
                continue;
            }
            if (inQuote) continue;
            if (c == ':' || c == ';') return i;
            if (c == '-' || c == '\u2013' || c == '\u2014') {
                boolean leftSpace = i == 0 || Character.isWhitespace(text.charAt(i - 1));
                boolean rightSpace = i + 1 >= text.length() || Character.isWhitespace(text.charAt(i + 1));
                if (leftSpace || rightSpace) return i;
            }
        }
        return -1;
    }

    /** Позиция первого якоря клаузы (по нижнему регистру текста) или -1. */
    private static int firstClauseMarkerStart(String text) {
        if (text == null || text.isBlank()) return -1;
        String lower = lower(text);
        int best = Integer.MAX_VALUE;
        for (Pattern pattern : MARKER_PATTERNS) {
            Matcher m = pattern.matcher(lower);
            if (m.find()) best = Math.min(best, m.start());
        }
        return best == Integer.MAX_VALUE ? -1 : best;
    }

    /** Позиция первого глагола выборки в середине текста или -1. */
    private static int firstSelectVerbStart(String text) {
        if (text == null || text.isBlank()) return -1;
        Matcher m = SELECT_VERB_MID.matcher(lower(text));
        return m.find() ? m.start() : -1;
    }

    private static Kind markerKind(String marker) {
        String m = marker;
        if (m.startsWith("сгруппир") || m.startsWith("группир") || m.contains("группировку")) {
            return Kind.GROUP;
        }
        if (m.startsWith("отсортир") || m.startsWith("сортир") || m.contains("сортировку")
                || m.startsWith("упорядоч")) {
            return Kind.ORDER;
        }
        if (m.startsWith("где") || m.startsWith("у которых") || m.startsWith("которые")
                || m.startsWith("соответствующие") || m.startsWith("при условии")) {
            return Kind.WHERE;
        }
        return Kind.TOTALS;
    }

    private static List<Pattern> buildMarkerPatterns() {
        List<Pattern> patterns = new ArrayList<>();
        for (String marker : CLAUSE_MARKERS) {
            patterns.add(Pattern.compile(Pattern.quote(marker)));
        }
        return patterns;
    }

    // === Разрешение имён по каталогу ===

    private QueryBuilderMetadataCatalog.Entity resolveSource(String sourceText, List<String> warnings) {
        if (catalog == null) {
            warnings.add("Каталог недоступен — разбор невозможен.");
            return null;
        }
        List<QueryBuilderMetadataCatalog.Entity> roots = catalog.roots();
        if (roots.isEmpty()) {
            warnings.add("Каталог не содержит разрешённых сущностей.");
            return null;
        }
        String token = normalize(sourceText);
        if (token.isBlank()) {
            warnings.add("Источник не указан — беру первую сущность каталога.");
            return roots.get(0);
        }
        String lower = lower(token);
        // Терпим окончания: «спецификации» → алиас «спецификация».
        String stemmed = stem(lower);
        for (Map.Entry<String, String> entry : sourceAliases.entrySet()) {
            String key = stem(entry.getKey());
            if (key.length() >= 3 && (key.equals(stemmed)
                    || key.startsWith(stemmed) || stemmed.startsWith(key))) {
                QueryBuilderMetadataCatalog.Entity e = findRoot(roots, entry.getValue());
                if (e != null) return e;
            }
        }
        for (QueryBuilderMetadataCatalog.Entity e : roots) {
            if (lower(e.entityName()).equals(lower)) return e;
        }
        return null;
    }

    private static QueryBuilderMetadataCatalog.Entity findRoot(
            List<QueryBuilderMetadataCatalog.Entity> roots, String entityName) {
        return roots.stream().filter(e -> e.entityName().equals(entityName)).findFirst().orElse(null);
    }

    private void resolveSelect(String selectPart, QueryBuilderMetadataCatalog.Entity entity,
                               String alias, List<SelectField> selects, List<String> warnings) {
        String body = selectPart;
        Matcher verb = SELECT_VERB.matcher(body);
        if (verb.find()) body = body.substring(verb.end()).trim();
        if (body.isBlank()) return; // предупреждение о пустой выборке добавляет doParse
        List<String> resolved = resolveList(body, entity, alias, warnings);
        Set<String> seen = new LinkedHashSet<>();
        for (String path : resolved) {
            if (!seen.add(path)) continue;
            String fieldName = path.substring(path.indexOf('.') + 1);
            selects.add(new SelectField(path, fieldName));
        }
    }

    private void resolveFields(String body, QueryBuilderMetadataCatalog.Entity entity,
                               String alias, List<String> groupBy, List<String> warnings) {
        List<String> resolved = resolveList(body, entity, alias, warnings);
        for (String path : resolved) {
            if (!groupBy.contains(path)) groupBy.add(path);
        }
    }

    private void resolveOrders(String body, QueryBuilderMetadataCatalog.Entity entity,
                               String alias, List<VisualQueryOrder> orders, List<String> warnings) {
        String clean = stripArticle(body);
        VisualQueryOrder.Direction direction = directionOf(clean);
        // Убираем служебные слова направления, чтобы осталась только фраза поля.
        String fieldPhrase = clean.replaceAll(
                "(?i)\\s+(?:по|в)\\s+(?:возрастанию|убыванию|возрастающей|убывающей|нисходящей|обратной)",
                "").trim();
        String path = resolveOneCounted(normalize(fieldPhrase), entity, alias);
        if (path == null) {
            warnings.add("Поле сортировки «" + fieldPhrase + "» не найдено.");
            return;
        }
        orders.add(new VisualQueryOrder(path, direction));
    }

    private static VisualQueryOrder.Direction directionOf(String body) {
        String b = lower(body);
        if (containsAny(b, "убывани", "нисходящ", "обратн", "наоборот") || DESC_WORD.matcher(b).find()) {
            return VisualQueryOrder.Direction.DESC;
        }
        if (containsAny(b, "возрастани", "возрастающ") || ASC_WORD.matcher(b).find()) {
            return VisualQueryOrder.Direction.ASC;
        }
        return VisualQueryOrder.Direction.ASC;
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) return true;
        }
        return false;
    }

    /**
     * Разбор клаузы «итоги по …»: может называть меру («сумма стоимости»,
     * «среднее по сумме») либо ключ группировки (как раньше — COUNT + включение
     * в GROUP BY). Для числового поля без явной функции используется SUM.
     */
    private boolean resolveTotals(String body, QueryBuilderMetadataCatalog.Entity entity, String alias,
                                  List<String> groupBy, List<Aggregate> aggregates, List<String> warnings) {
        String clean = stripArticle(body);
        if (clean.isBlank()) {
            warnings.add("«Итоги» без указания полей — игнорируется.");
            return true;
        }
        // Выделяем слово-функцию (сумма/среднее/максимум/минимум/количество), остальное — мера.
        String function = null;
        List<String> measureTokens = new ArrayList<>();
        for (String word : clean.split("\\s+")) {
            String fw = functionOfWord(word);
            if (fw != null) {
                function = (function == null) ? fw : function;
            } else {
                measureTokens.add(word);
            }
        }
        String measurePhrase = String.join(" ", measureTokens);
        if (measurePhrase.isBlank()) {
            warnings.add("«Итоги» без поля-меры — игнорируется.");
            return true;
        }
        String path = resolveOneCounted(normalize(measurePhrase), entity, alias);
        if (path == null) {
            warnings.add("Поле «" + measurePhrase + "» не найдено (в «итоги»).");
            return true;
        }
        boolean numeric = isNumericPath(entity, path);

        if (function == null) {
            if (numeric && !groupBy.isEmpty()) {
                aggregates.add(new Aggregate("SUM", path, uniqueAggName(aggregates)));
                warnings.add("«Итоги по …» по числовому полю трактуются как SUM.");
            } else {
                if (!groupBy.contains(path)) groupBy.add(path);
                aggregates.add(new Aggregate("COUNT_ROWS", alias + ".id", uniqueAggName(aggregates)));
            }
            return true;
        }

        if (requiresNumeric(function) && !numeric) {
            warnings.add("Функция «" + function + "» требует числового поля «" + measurePhrase
                    + "» — использую COUNT.");
            function = "COUNT";
        }
        aggregates.add(new Aggregate(function, path, uniqueAggName(aggregates)));
        return true;
    }

    /** Возвращает агрегат для слова-функции (нижний регистр слова) или null. */
    private static String functionOfWord(String word) {
        String w = lower(word);
        if (w.startsWith("сумм")) return "SUM";
        if (w.startsWith("средн") || w.startsWith("арифмет")) return "AVG";
        if (w.startsWith("максим")) return "MAX";
        if (w.startsWith("минимум") || w.startsWith("минимал") || w.equals("мин")) return "MIN";
        if (w.startsWith("колич") || w.startsWith("кол")) return "COUNT";
        return null;
    }

    private static boolean requiresNumeric(String function) {
        return "SUM".equals(function) || "AVG".equals(function);
    }

    private boolean isNumericPath(QueryBuilderMetadataCatalog.Entity entity, String path) {
        String fieldName = path.substring(path.lastIndexOf('.') + 1);
        return entity.fields().stream().anyMatch(f -> f.name().equals(fieldName) && f.aggregatable());
    }

    private static String uniqueAggName(List<Aggregate> aggregates) {
        return "aggr" + (aggregates.size() + 1);
    }

    // === Условия (WHERE) ===

    /**
     * Разбор клаузы «где …» в дерево фильтра. Тело приходит в нижнем регистре от
     * {@code collectClauses}. Разделители И/ИЛИ/запятая группируют условия;
     * каждое условие — «поле оператор значение» (равно/содержит/больше/между и т.п.,
     * либо символьные =, <>, >, <, >=, <=). Значение в кавычках или без.
     */
    private org.ipro.filter.FilterNode resolveWhere(String body,
                                                    QueryBuilderMetadataCatalog.Entity entity,
                                                    String alias, List<String> warnings) {
        String clean = stripArticle(body);
        if (clean.isBlank()) {
            warnings.add("«Где» без условий — игнорируется.");
            return null;
        }
        // Защищаем «между X и Y»: внутреннее «и» замещаем маркером, чтобы общий разрез
        // по «и» не разорвал пару значений на два отдельных условия.
        clean = protectBetween(clean);
        List<String> orGroups = splitTopLevel(clean, "или");
        List<org.ipro.filter.FilterNode> orItems = new ArrayList<>();
        for (String orGroup : orGroups) {
            List<org.ipro.filter.FilterNode> andItems = new ArrayList<>();
            for (String conditionPart : splitTopLevel(orGroup, "и")) {
                for (String condStr : splitByComma(conditionPart)) {
                    org.ipro.filter.FilterNode condition = resolveCondition(condStr, entity, alias, warnings);
                    if (condition != null) andItems.add(condition);
                }
            }
            if (!andItems.isEmpty()) {
                orItems.add(andItems.size() == 1 ? andItems.get(0)
                        : new FilterGroup(LogicalOperator.AND, andItems));
            }
        }
        if (orItems.isEmpty()) return null;
        return orItems.size() == 1 ? orItems.get(0) : new FilterGroup(LogicalOperator.OR, orItems);
    }

    /** Разбиение на верхнем уровне по слову-разделителю (не внутри кавычек). */
    private static List<String> splitTopLevel(String text, String separator) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') inQuote = !inQuote;
            if (!inQuote && text.regionMatches(true, i, separator, 0, separator.length())
                    && boundaryOk(text, i, separator.length())) {
                result.add(current.toString().trim());
                current.setLength(0);
                i += separator.length() - 1;
            } else {
                current.append(c);
            }
        }
        result.add(current.toString().trim());
        result.removeIf(String::isBlank);
        return result;
    }

    /** Граница ключевого слова: с обеих сторон не-буква либо края. */
    private static boolean boundaryOk(String text, int start, int length) {
        int before = start - 1;
        int after = start + length;
        return (before < 0 || !Character.isLetter(text.charAt(before)))
                && (after >= text.length() || !Character.isLetter(text.charAt(after)));
    }

    private static List<String> splitByComma(String text) {
        List<String> result = new ArrayList<>();
        for (String piece : text.split("\\s*[,;]\\s*")) {
            if (!piece.isBlank()) result.add(piece.trim());
        }
        return result;
    }

    /** Символ-маркер, замещающий «и» внутри «между X и Y» перед разрезанием по «и». */
    private static final String PROTECT_BETWEEN = "\u0001";

    /** «между … и …» → «между … {PROTECT_BETWEEN} …», вне кавычек. */
    private static String protectBetween(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 8);
        boolean inQuote = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') inQuote = !inQuote;
            if (!inQuote && i + 5 <= text.length() && text.regionMatches(true, i, "между", 0, 5)) {
                int after = i + 5;
                boolean q = inQuote;
                int j = after;
                int close = -1;
                while (j < text.length()) {
                    char cc = text.charAt(j);
                    if (cc == '\'') q = !q;
                    if (!q && cc == 'и' && boundaryOk(text, j, 1)) {
                        close = j;
                        break;
                    }
                    j++;
                }
                if (close > after) {
                    sb.append(text, i, close).append(PROTECT_BETWEEN);
                    i = close; // «и» пропускаем — заменено маркером
                } else {
                    sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Первый оператор условия — слово («равно/содержит/между/…») или символ (=, <>, >= и т.п.). */
    private static OperatorFound findOperator(String cond) {
        String lower = lower(cond);
        OperatorFound best = null;
        // Слова-операторы (проверяем сначала более длинные фразы — «больше или равно»).
        String[][] words = {
                {"больше или равно", "GE"},
                {"меньше или равно", "LE"},
                {"начинается с", "STARTS_WITH"},
                {"начинается на", "STARTS_WITH"},
                {"заканчивается на", "STARTS_WITH"},
                {"не равно", "NE"},
                {"не равен", "NE"},
                {"не меньше", "GE"},
                {"не больше", "LE"},
                {"больше", "GT"},
                {"меньше", "LT"},
                {"равно", "EQ"},
                {"равен", "EQ"},
                {"содержит", "CONTAINS"},
                {"содержащий", "CONTAINS"},
                {"между", "BETWEEN"},
                {"заполнено", "IS_NOT_NULL"},
                {"не пусто", "IS_NOT_NULL"},
                {"пусто", "IS_NULL"},
                {"не определено", "IS_NULL"},
        };
        for (String[] pair : words) {
            int idx = indexOfWord(lower, pair[0], 0);
            if (idx < 0) continue;
            OperatorFound candidate = new OperatorFound(idx, idx + pair[0].length(),
                    FilterOperator.valueOf(pair[1]));
            if (candidate.start() >= 0 && (best == null || best.start() > candidate.start())) {
                best = candidate;
            }
        }
        // Символьные операторы.
        String[][] symbols = {
                {">=", "GE"}, {"<=", "LE"}, {"<>", "NE"}, {"!=", "NE"},
                {"=", "EQ"}, {">", "GT"}, {"<", "LT"},
        };
        for (String[] pair : symbols) {
            int idx = lower.indexOf(pair[0]);
            if (idx < 0) continue;
            OperatorFound candidate = new OperatorFound(idx, idx + pair[0].length(),
                    FilterOperator.valueOf(pair[1]));
            if (best == null || candidate.start() < best.start()) {
                best = candidate;
            }
        }
        return best;
    }

    /** Индекс вхождения слова-оператора с границей слова (не внутри другого слова). */
    private static int indexOfWord(String text, String word, int from) {
        int idx = from;
        while (true) {
            idx = text.indexOf(word, idx);
            if (idx < 0) return -1;
            if (boundaryOk(text, idx, word.length())) return idx;
            idx++;
        }
    }

    /** Срезает одинарные кавычки с краёв значения. */
    private static String trimQuotes(String value) {
        if (value == null) return "";
        String v = value.trim();
        if (v.length() >= 2 && v.startsWith("'") && v.endsWith("'")) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    /** Тип значения фильтра по полю (для FilterCondition.dataType). */
    private static FilterDataType dataTypeOf(QueryBuilderMetadataCatalog.Entity entity, String path) {
        String fieldName = path.substring(path.lastIndexOf('.') + 1);
        Class<?> type = entity.fields().stream()
                .filter(f -> f.name().equals(fieldName)).map(QueryBuilderMetadataCatalog.Field::javaType)
                .findFirst().orElse(Object.class);
        if (type.isEnum()) return FilterDataType.ENUM;
        if (Number.class.isAssignableFrom(type) || type.isPrimitive()) return FilterDataType.NUMBER;
        if (type == Boolean.class || type == boolean.class) return FilterDataType.BOOLEAN;
        if (java.time.temporal.Temporal.class.isAssignableFrom(type)) return FilterDataType.DATE;
        return FilterDataType.TEXT;
    }

    /** Объединяет несколько WHERE-клауз (из нескольких «где …») в одну AND-группу. */
    private org.ipro.filter.FilterNode combineWhere(List<org.ipro.filter.FilterNode> nodes) {
        if (nodes.isEmpty()) return null;
        if (nodes.size() == 1) return nodes.get(0);
        return new FilterGroup(LogicalOperator.AND, nodes);
    }

    /** Условие «поле оператор значение»: слово-оператор, символьный или по умолчанию Равно. */
    private org.ipro.filter.FilterNode resolveCondition(String raw, QueryBuilderMetadataCatalog.Entity entity,
                                                        String alias, List<String> warnings) {
        String cond = raw.trim();
        if (cond.isBlank()) return null;
        OperatorFound found = findOperator(cond);

        String fieldPhrase;
        String valuePhrase;
        FilterOperator operator;
        if (found == null) {
            String[] firstToken = cond.split("\\s+", 2);
            fieldPhrase = firstToken[0];
            valuePhrase = firstToken.length > 1 ? firstToken[1].trim() : "";
            operator = FilterOperator.EQ;
        } else {
            fieldPhrase = cond.substring(0, found.start).trim();
            valuePhrase = cond.substring(found.end).trim();
            operator = found.operator;
        }

        if (operator == FilterOperator.IS_NULL || operator == FilterOperator.IS_NOT_NULL) {
            String path = resolveOneCounted(fieldPhrase, entity, alias);
            if (path == null) {
                warnings.add("Поле условия «" + fieldPhrase + "» не найдено.");
                return null;
            }
            return FilterConditionNode.of(new FilterCondition(path, operator, null, null,
                    dataTypeOf(entity, path)));
        }

        if (operator == FilterOperator.BETWEEN) {
            // «между X и Y»: внутреннее «и» уже замещено маркером PROTECT_BETWEEN.
            int marker = valuePhrase.indexOf(PROTECT_BETWEEN);
            String first;
            String second;
            if (marker > 0) {
                first = valuePhrase.substring(0, marker).trim();
                second = valuePhrase.substring(marker + PROTECT_BETWEEN.length()).trim();
            } else {
                List<String> parts = splitTopLevel(valuePhrase, "и");
                if (parts.size() != 2) {
                    warnings.add("«Между» требует два значения: «" + valuePhrase + "».");
                    return null;
                }
                first = parts.get(0);
                second = parts.get(1);
            }
            String path = resolveOneCounted(fieldPhrase, entity, alias);
            if (path == null) {
                warnings.add("Поле условия «" + fieldPhrase + "» не найдено.");
                return null;
            }
            return FilterConditionNode.of(new FilterCondition(path, operator,
                    trimQuotes(first), trimQuotes(second), dataTypeOf(entity, path)));
        }

        String value = trimQuotes(valuePhrase);
        if (value.isEmpty()) {
            warnings.add("Условие «" + cond + "» без значения — пропущено.");
            return null;
        }
        String path = resolveOneCounted(fieldPhrase, entity, alias);
        if (path == null) {
            warnings.add("Поле условия «" + fieldPhrase + "» не найдено.");
            return null;
        }
        return FilterConditionNode.of(new FilterCondition(path, operator, value, null,
                dataTypeOf(entity, path)));
    }

    private record OperatorFound(int start, int end, FilterOperator operator) { }

    /** Разрыв списка полей на элементы списка и их разрешение; ненайденные — в warnings. */
    private List<String> resolveList(String raw, QueryBuilderMetadataCatalog.Entity entity,
                                     String alias, List<String> warnings) {
        String[] items = ITEM_SPLIT.split(stripArticle(raw));
        List<String> result = new ArrayList<>();
        for (String item : items) {
            String token = normalize(item);
            if (token.isBlank()) continue;
            tokensAttempted++;
            String path = resolveOne(token, entity, alias);
            if (path == null) {
                warnings.add("Поле «" + token + "» не найдено в каталоге сущности «"
                        + entity.entityName() + "».");
                continue;
            }
            tokensResolved++;
            result.add(path);
        }
        return result;
    }

    /**
     * Разрешение одного имени поля: словарь, затем подпись поля/связи, затем —
     * путь сквозь связь (точечная нотация или «поле связи»), наконец мягкое
     * сопоставление подстроки. Возвращает null и при неоднозначности.
     */
    private String resolveOne(String token, QueryBuilderMetadataCatalog.Entity entity, String alias) {
        if (token.isBlank()) return null;
        String lowerToken = lower(token);

        String byAlias = fieldAliases.get(lowerToken);
        if (byAlias != null && fieldOf(entity, byAlias)) {
            return alias + "." + byAlias;
        }

        // Точные (точное имя/подпись/стемм) совпадения, включая aliases подписей
        // колонок грида (вложенные «Ед.изм.Наименование» и кастомные заголовки).
        List<String> exact = new ArrayList<>();
        for (QueryBuilderMetadataCatalog.Field field : entity.fields()) {
            if (lower(field.name()).equals(lowerToken) || captionMatches(field.caption(), token)
                    || captionMatchesAny(field.aliases(), token)) {
                exact.add(alias + "." + field.name());
            }
        }
        if (exact.isEmpty()) {
            for (QueryBuilderMetadataCatalog.Association assoc : entity.associations()) {
                if (lower(assoc.name()).equals(lowerToken) || captionMatches(assoc.caption(), token)) {
                    exact.add(alias + "." + assoc.name());
                }
            }
        }
        if (exact.size() == 1) return exact.get(0);
        if (exact.size() > 1) return null; // неоднозначно — не угадываем

        // Путь сквозь ассоциацию.
        String throughAssoc = resolveThroughAssociation(entity, alias, token);
        if (throughAssoc != null) return throughAssoc;

        // Мягкое сопоставление: подпись содержит слово или слово содержит подпись.
        List<String> soft = new ArrayList<>();
        for (QueryBuilderMetadataCatalog.Field field : entity.fields()) {
            if (captionContains(field.caption(), token)) soft.add(alias + "." + field.name());
        }
        if (soft.size() == 1) return soft.get(0);
        return null; // 0 — нет; >1 — неоднозначно.
    }

    private boolean fieldOf(QueryBuilderMetadataCatalog.Entity entity, String name) {
        return entity.fields().stream().anyMatch(f -> f.name().equals(name));
    }

    private static boolean equalsCaption(String caption, String token) {
        return lower(caption.trim()).equals(lower(token));
    }

    /** Подпись совпадает со словом в любой форме окончания («наименованию» → «Наименование»). */
    private static boolean captionMatches(String caption, String token) {
        return equalsCaption(caption, token) || stem(caption).equals(stem(token));
    }

    /** Хотя бы один заголовок колонки матчит токен (стемм/точное). */
    private static boolean captionMatchesAny(List<String> captions, String token) {
        if (captions == null || captions.isEmpty()) return false;
        for (String caption : captions) {
            if (captionMatches(caption, token)) return true;
        }
        return false;
    }

    private static boolean captionContains(String caption, String token) {
        String c = lower(caption.trim());
        String t = lower(token);
        return !t.isEmpty() && (c.contains(t) || t.contains(c));
    }

    // === Пути сквозь ассоциации (JOIN) ===

    /**
     * Пытается разрешить «категория.наименование» (точечная) либо «наименование
     * категории»/«категория наименование» — через ассоциацию корневой сущности,
     * порождая промежуточные JOIN. Возвращает путь вида {@code <joinAlias>.<поле>}.
     */
    private String resolveThroughAssociation(QueryBuilderMetadataCatalog.Entity entity, String alias,
                                             String token) {
        String lt = lower(token);
        String[] segments;
        if (lt.contains(".")) {
            segments = lt.split("\\.");
        } else {
            String[] words = lt.split("\\s+");
            if (words.length < 2) return null;
            // Декомпозиция «связь + поле» в обоих порядках: «категория наименование»
            // и родительный падеж «наименование категории».
            for (int split = 1; split < words.length; split++) {
                String assocPhrase = String.join(" ", Arrays.copyOfRange(words, 0, split));
                String fieldPhrase = String.join(" ", Arrays.copyOfRange(words, split, words.length));
                String hit = throughChain(entity, alias, assocPhrase, fieldPhrase);
                if (hit != null) return hit;
            }
            for (int split = 1; split < words.length; split++) {
                String fieldPhrase = String.join(" ", Arrays.copyOfRange(words, 0, split));
                String assocPhrase = String.join(" ", Arrays.copyOfRange(words, split, words.length));
                String hit = throughChain(entity, alias, assocPhrase, fieldPhrase);
                if (hit != null) return hit;
            }
            return null;
        }
        if (segments.length < 2) return null;
        String assocPhrase = String.join(" ", Arrays.copyOfRange(segments, 0, segments.length - 1));
        String fieldPhrase = segments[segments.length - 1].trim();
        return throughChain(entity, alias, assocPhrase, fieldPhrase);
    }

    private String throughChain(QueryBuilderMetadataCatalog.Entity entity, String alias,
                                String assocPhrase, String fieldPhrase) {
        if (assocPhrase.isBlank() || fieldPhrase.isBlank()) return null;
        QueryBuilderMetadataCatalog.Entity current = entity;
        String currentAlias = alias;
        String remaining = lower(assocPhrase);
        // Разбираем связку из нескольких ассоциаций последовательно (если встречается несколько подряд).
        boolean progressed;
        do {
            progressed = false;
            for (QueryBuilderMetadataCatalog.Association assoc : current.associations()) {
                if (lower(assoc.name()).equals(remaining)
                        || captionMatches(assoc.caption(), remaining)) {
                    QueryBuilderMetadataCatalog.Entity target = entityByType(assoc.targetType());
                    if (target == null) return null;
                    currentAlias = joinFor(currentAlias, assoc, target);
                    current = target;
                    remaining = "";
                    progressed = true;
                    break;
                }
            }
        } while (progressed);
        if (!remaining.isBlank()) return null;
        String fieldPath = resolveOneWithoutCounting(fieldPhrase, current);
        if (fieldPath == null) return null;
        // Путь в целевой сущности строится по алиасу объединения.
        String leaf = fieldPath;
        int dot = leaf.indexOf('.');
        leaf = dot < 0 ? leaf : leaf.substring(dot + 1);
        return currentAlias + "." + leaf;
    }

    /** resolveOne без счётчиков (для вложенного пути по связи). */
    private String resolveOneWithoutCounting(String token, QueryBuilderMetadataCatalog.Entity entity) {
        return resolveOne(token, entity, "x");
    }

    /** resolveOne с учётом одного токена в confidence (используется в where/order). */
    private String resolveOneCounted(String token, QueryBuilderMetadataCatalog.Entity entity, String alias) {
        tokensAttempted++;
        String path = resolveOne(token, entity, alias);
        if (path != null) tokensResolved++;
        return path;
    }

    private QueryBuilderMetadataCatalog.Entity entityByType(Class<?> type) {
        if (catalog == null || type == null) return null;
        return catalog.roots().stream().filter(e -> e.javaType().equals(type)).findFirst().orElse(null);
    }

    /** Создаёт/переиспользует JOIN по ассоциации и возвращает его alias. */
    private String joinFor(String parentAlias, QueryBuilderMetadataCatalog.Association assoc,
                           QueryBuilderMetadataCatalog.Entity target) {
        String key = parentAlias + "|" + assoc.name();
        String existing = joinAliasByKey.get(key);
        if (existing != null) return existing;
        String base = lowerCamel(target.entityName());
        String alias = uniqueJoinAlias(base);
        joins.add(new VisualQueryDefinition.Join(parentAlias, assoc.name(), alias,
                VisualQueryDefinition.JoinKind.INNER));
        joinAliasByKey.put(key, alias);
        return alias;
    }

    private String uniqueJoinAlias(String base) {
        String candidate = base;
        int index = 2;
        while (joinAliasTaken(candidate)) candidate = base + index++;
        return candidate;
    }

    private boolean joinAliasTaken(String alias) {
        if (joins.stream().anyMatch(j -> j.alias().equals(alias))) return true;
        return false;
    }

    private static String lowerCamel(String name) {
        if (name == null || name.isBlank()) return "t";
        return name.substring(0, 1).toLowerCase(Locale.ROOT)
                + name.substring(1).replaceAll("[^A-Za-z0-9_]", "");
    }

    // === Утилиты ===

    /** Нормализация фразы поля: срезаем предлоги/пробелы, убираем «по». */
    private static String stripArticle(String text) {
        if (text == null) return "";
        String t = text.trim();
        Matcher m = LEADING_ARTICLE.matcher(t);
        if (m.find()) t = t.substring(m.end()).trim();
        return t.trim();
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim();
    }

    private static String lower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).trim();
    }

    /** Примитивная нормализация рода/падежа: срезаем конечные гласные (и «ь»). */
    private static String stem(String text) {
        String t = lower(text);
        while (t.length() > 3 && "аеиоуыэюяйь".indexOf(t.charAt(t.length() - 1)) >= 0) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}