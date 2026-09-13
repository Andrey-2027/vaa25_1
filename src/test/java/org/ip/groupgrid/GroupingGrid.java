package org.ip.groupgrid;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridSortOrder;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.data.provider.ListDataProvider;
import com.vaadin.flow.data.provider.SortDirection;

/**
 * Эмуляция группировки строк поверх обычного Grid (in-memory данные).
 *
 * Строки-заголовки групп вставляются как обычные элементы ListDataProvider,
 * сворачивание реализовано через фильтрацию провайдера, сортировка колонок —
 * через собственный компаратор по исходным элементам.
 *
 * Ограничения: только in-memory; выделение строк отключено.
 */
@CssImport("./styles/grouping-grid.css")
public class GroupingGrid<T> extends Grid<GroupingGrid.GroupRow<T>> {

    /**
     * Обёртка строки: либо группа (item == null), либо обычный элемент (items == null).
     */
    public record GroupRow<T>(String groupKey, List<T> items, T item) {

        public boolean isGroup() {
            return item == null;
        }

        public String groupTitle() {
            return groupKey;
        }

        public int groupSize() {
            return isGroup() ? items.size() : 1;
        }

        public static <T> GroupRow<T> group(String key, List<T> items) {
            return new GroupRow<>(key, items, null);
        }

        public static <T> GroupRow<T> item(T item) {
            return new GroupRow<>(null, null, item);
        }
    }

    private final ListDataProvider<GroupRow<T>> dataProvider =
            new ListDataProvider<>(new ArrayList<>());

    private Collection<T> sourceItems = List.of();
    private Function<T, String> groupKeyExtractor;

    private final Map<String, Function<T, ?>> sortExtractors = new HashMap<>();
    private final Map<String, Function<Object, String>> displayFormatters = new HashMap<>();
    private final Map<String, Function<List<T>, String>> aggregators = new HashMap<>();

    private final Set<String> collapsedGroups = new HashSet<>();
    private Comparator<T> sortComparator;
    private String firstDataColumnKey;
    private int keySeq;

    public GroupingGrid() {
        setDataProvider(dataProvider);
        addClassName("grouping-grid");

        setSelectionMode(SelectionMode.SINGLE);
        setPartNameGenerator(row -> row != null && row.isGroup() ? "group-row" : null);

        addComponentColumn(this::createToggle)
                .setKey("gg-expander")
                .setFlexGrow(0)
                .setWidth("56px")
                .setSortable(false);

        addItemClickListener(e -> {
            if (e.getItem() != null && e.getItem().isGroup()) {
                toggleGroup(e.getItem().groupKey());
            }
        });

        addSortListener(e -> applySortOrders(e.getSortOrder()));
    }

    public void setItems(Collection<T> items, Function<T, String> groupKeyExtractor) {
        this.sourceItems = Objects.requireNonNull(items, "items");
        this.groupKeyExtractor = groupKeyExtractor;
        this.collapsedGroups.clear();
        rebuild();
    }

    public Column<GroupRow<T>> addItemColumn(Function<T, ?> extractor, String header) {
        String key = "gg-col-" + (++keySeq);
        if (firstDataColumnKey == null) {
            firstDataColumnKey = key;
        }
        sortExtractors.put(key, extractor);
        return addComponentColumn(row -> cellContent(row, key))
                .setKey(key)
                .setHeader(header)
                .setSortable(true);
    }

    public <V> Column<GroupRow<T>> addItemColumn(Function<T, V> extractor, String header,
            Function<V, String> formatter) {
        Column<GroupRow<T>> column = addItemColumn(extractor, header);
        displayFormatters.put(column.getKey(), raw -> formatter.apply((V) raw));
        return column;
    }

    public void setAggregator(Column<GroupRow<T>> column, Function<List<T>, String> aggregator) {
        aggregators.put(column.getKey(), aggregator);
    }

    public void collapseAll() {
        if (groupKeyExtractor == null) {
            return;
        }
        sourceItems.stream().map(groupKeyExtractor).forEach(collapsedGroups::add);
        rebuild();
    }

    public void expandAll() {
        collapsedGroups.clear();
        rebuild();
    }

    public void toggleGroup(String groupKey) {
        if (!collapsedGroups.remove(groupKey)) {
            collapsedGroups.add(groupKey);
        }
        rebuild();
    }

    public boolean isCollapsed(String groupKey) {
        return collapsedGroups.contains(groupKey);
    }

    public void refresh() {
        rebuild();
    }

    private Component cellContent(GroupRow<T> row, String key) {
        if (row.isGroup()) {
            Function<List<T>, String> aggregator = aggregators.get(key);
            String text;
            if (aggregator != null) {
                text = aggregator.apply(row.items());
            } else if (key.equals(firstDataColumnKey)) {
                text = row.groupTitle() + " (" + row.groupSize() + ")";
            } else {
                text = "";
            }
            Span span = new Span(text);
            span.getStyle().set("font-weight", "600");
            return span;
        }
        Object raw = sortExtractors.get(key).apply(row.item());
        Function<Object, String> formatter = displayFormatters.get(key);
        return new Span(formatter != null ? formatter.apply(raw) : String.valueOf(raw));
    }

    private Component createToggle(GroupRow<T> row) {
        if (!row.isGroup()) {
            return new Span("");
        }
        boolean collapsed = collapsedGroups.contains(row.groupKey());
        Button button = new Button(new Icon(
                collapsed ? VaadinIcon.CHEVRON_RIGHT : VaadinIcon.CHEVRON_DOWN));
        button.addThemeVariants(ButtonVariant.LUMO_ICON);
        button.getElement().setAttribute("aria-label", collapsed ? "Развернуть" : "Свернуть");
        button.addClickListener(e -> toggleGroup(row.groupKey()));
        return button;
    }

    private void applySortOrders(List<GridSortOrder<GroupRow<T>>> sortOrder) {
        Comparator<T> result = null;
        for (GridSortOrder<GroupRow<T>> order : sortOrder) {
            Function<T, ?> extractor = sortExtractors.get(order.getSorted().getKey());
            if (extractor == null) {
                continue;
            }
            Comparator<T> cmp = comparingBy(extractor);
            if (order.getDirection() == SortDirection.DESCENDING) {
                cmp = cmp.reversed();
            }
            result = result == null ? cmp : result.thenComparing(cmp);
        }
        sortComparator = result;
        rebuild();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Comparator<T> comparingBy(Function<T, ?> extractor) {
        return Comparator.comparing(t -> (Comparable) extractor.apply(t),
                Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private void rebuild() {
        List<GroupRow<T>> rows = new ArrayList<>();
        if (groupKeyExtractor == null) {
            sourceItems.forEach(i -> rows.add(GroupRow.item(i)));
        } else {
            Map<String, List<T>> groups = new LinkedHashMap<>();
            var stream = sourceItems.stream();
            if (sortComparator != null) {
                stream = stream.sorted(sortComparator);
            }
            stream.forEach(i ->
                    groups.computeIfAbsent(groupKeyExtractor.apply(i), k -> new ArrayList<>()).add(i));

            for (Map.Entry<String, List<T>> group : groups.entrySet()) {
                rows.add(GroupRow.group(group.getKey(), group.getValue()));
                if (!collapsedGroups.contains(group.getKey())) {
                    group.getValue().forEach(i -> rows.add(GroupRow.item(i)));
                }
            }
        }
        dataProvider.getItems().clear();
        dataProvider.getItems().addAll(rows);
        dataProvider.refreshAll();
    }
}
