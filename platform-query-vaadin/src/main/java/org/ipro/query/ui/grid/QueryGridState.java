package org.ipro.query.ui.grid;

import org.ipro.filtergrid.FooterAggregateKind;
import org.ipro.filtergrid.filter.FilterNode;
import org.ipro.filtergrid.grouping.GroupAggregation;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.GridViewState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Модель настроек сетки запроса: то, что пользователь настроил и что можно сохранить.
 *
 * <p>Хранится в формате {@link GridViewState} — том же, что у «Видов» списков платформы. Колонки (алиас
 * запроса, заголовок, ширина), отбор и их порядок лежат в стандартных полях. Чего в нём нет,
 * идёт в {@code options} с префиксом {@code query.}: сортировка, группировка, итоги и скрытые
 * колонки. Скрытые перечисляются явно, чтобы отличать их от новых колонок запроса: новая колонка
 * появляется видимой.</p>
 *
 * @param columns видимые колонки в порядке показа; колонок запроса, которых здесь нет и которые
 *                не скрыты, сетка добавляет в конец с заголовком из запроса
 * @param hidden алиасы явно скрытых колонок
 * @param filter отбор сетки; не включает выбранный узел группировки
 */
public record QueryGridState(List<Column> columns, Set<String> hidden, List<Sort> sort, FilterNode filter,
                             List<String> groupLevels, List<Aggregate> groupAggregates,
                             Map<String, FooterAggregateKind> totals) {
    public static final String OPTION_SORT = "query.sort";
    public static final String OPTION_GROUP = "query.group";
    public static final String OPTION_GROUP_AGGREGATES = "query.groupAgg";
    public static final String OPTION_TOTALS = "query.totals";
    public static final String OPTION_HIDDEN = "query.hidden";

    /** Наибольшая вложенность группировки (UX-8). */
    public static final int MAX_GROUP_LEVELS = 3;

    public static final QueryGridState EMPTY = new QueryGridState(List.of(), Set.of(), List.of(), null,
            List.of(), List.of(), Map.of());

    /** Колонка: алиас запроса, заголовок и ширина; {@code null} — как в запросе и по умолчанию. */
    public record Column(String alias, String title, String width) {
        public Column {
            if (alias == null || alias.isBlank()) throw new IllegalArgumentException("alias");
            title = title == null || title.isBlank() ? null : title;
            width = width == null || width.isBlank() ? null : width;
        }
    }

    public record Sort(String alias, boolean descending) {
        public Sort {
            if (alias == null || alias.isBlank()) throw new IllegalArgumentException("alias");
        }
    }

    public record Aggregate(String alias, GroupAggregation.AggFunction function) {
        public Aggregate {
            if (alias == null || alias.isBlank() || function == null) throw new IllegalArgumentException("aggregate");
        }
    }

    public QueryGridState {
        columns = List.copyOf(columns == null ? List.of() : columns);
        hidden = Set.copyOf(hidden == null ? Set.of() : hidden);
        sort = List.copyOf(sort == null ? List.of() : sort);
        groupLevels = List.copyOf(groupLevels == null ? List.of() : groupLevels);
        groupAggregates = List.copyOf(groupAggregates == null ? List.of() : groupAggregates);
        totals = Map.copyOf(totals == null ? Map.of() : totals);
    }

    public QueryGridState withFilter(FilterNode value) {
        return new QueryGridState(columns, hidden, sort, value, groupLevels, groupAggregates, totals);
    }

    public QueryGridState withTotals(Map<String, FooterAggregateKind> value) {
        return new QueryGridState(columns, hidden, sort, filter, groupLevels, groupAggregates, value);
    }

    /** Состояние в формате видов. Один и тот же JSON читают «Виды» списка и документ UTR. */
    public GridViewState toViewState() {
        List<ColumnPath.Spec> specs = new ArrayList<>();
        for (Column column : columns) {
            specs.add(new ColumnPath.Spec(column.alias(), column.title(), column.width(), null, null));
        }
        Map<String, String> options = new LinkedHashMap<>();
        put(options, OPTION_SORT, sort.stream()
                .map(item -> item.alias() + ":" + (item.descending() ? "DESC" : "ASC")).toList());
        put(options, OPTION_GROUP, groupLevels);
        put(options, OPTION_GROUP_AGGREGATES, groupAggregates.stream()
                .map(item -> item.alias() + ":" + item.function().name()).toList());
        put(options, OPTION_TOTALS, totals.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(item -> item.getKey() + ":" + item.getValue().name()).toList());
        put(options, OPTION_HIDDEN, hidden.stream().sorted().toList());
        return new GridViewState(specs, List.of(), null, filter, options);
    }

    /**
     * Читает состояние из вида. Незнакомые и испорченные фрагменты пропускаются: вид, сохранённый
     * раньше, не должен мешать открыть список.
     */
    public static QueryGridState fromViewState(GridViewState view) {
        if (view == null) return EMPTY;
        List<Column> columns = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ColumnPath.Spec spec : view.columns()) {
            if (spec.path() != null && !spec.path().isBlank() && seen.add(spec.path())) {
                columns.add(new Column(spec.path(), spec.label(), spec.width()));
            }
        }
        Map<String, String> options = view.options();
        List<Sort> sort = new ArrayList<>();
        for (String[] pair : pairs(options.get(OPTION_SORT))) {
            if ("ASC".equals(pair[1]) || "DESC".equals(pair[1])) sort.add(new Sort(pair[0], "DESC".equals(pair[1])));
        }
        List<Aggregate> aggregates = new ArrayList<>();
        for (String[] pair : pairs(options.get(OPTION_GROUP_AGGREGATES))) {
            GroupAggregation.AggFunction function = parse(GroupAggregation.AggFunction.class, pair[1]);
            if (function != null) aggregates.add(new Aggregate(pair[0], function));
        }
        Map<String, FooterAggregateKind> totals = new LinkedHashMap<>();
        for (String[] pair : pairs(options.get(OPTION_TOTALS))) {
            FooterAggregateKind kind = parse(FooterAggregateKind.class, pair[1]);
            if (kind != null) totals.put(pair[0], kind);
        }
        return new QueryGridState(columns, new LinkedHashSet<>(split(options.get(OPTION_HIDDEN))), sort,
                view.userFilterOrLegacy(), split(options.get(OPTION_GROUP)).stream().distinct()
                        .limit(MAX_GROUP_LEVELS).toList(), aggregates, totals);
    }

    private static void put(Map<String, String> options, String key, List<String> values) {
        if (!values.isEmpty()) options.put(key, String.join(",", values));
    }

    private static List<String> split(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String token : text.split(",")) {
            if (!token.isBlank()) result.add(token.trim());
        }
        return result;
    }

    private static List<String[]> pairs(String text) {
        List<String[]> result = new ArrayList<>();
        for (String token : split(text)) {
            int colon = token.indexOf(':');
            if (colon > 0 && colon < token.length() - 1) {
                result.add(new String[] {token.substring(0, colon), token.substring(colon + 1)});
            }
        }
        return result;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String name) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
