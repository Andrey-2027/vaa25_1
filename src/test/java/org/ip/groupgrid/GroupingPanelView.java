package org.ip.groupgrid;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.listbox.ListBox;
import com.vaadin.flow.component.listbox.MultiSelectListBox;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.treegrid.TreeGrid;
import com.vaadin.flow.data.provider.CallbackDataProvider;
import com.vaadin.flow.data.provider.ConfigurableFilterDataProvider;
import com.vaadin.flow.data.provider.DataProvider;
import com.vaadin.flow.data.provider.SortDirection;
import com.vaadin.flow.data.provider.hierarchy.AbstractBackEndHierarchicalDataProvider;
import com.vaadin.flow.data.provider.hierarchy.HierarchicalQuery;
import com.vaadin.flow.data.renderer.TextRenderer;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

import jakarta.annotation.security.PermitAll;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import org.ip.model.Nomenclature;

/**
 * Демо "панель группировки": обычный ленивый Grid слева, справа панель,
 * где выбираются поля группировки и строится ленивое дерево их значений.
 * Клик по узлу дерева фильтрует Grid по всему пути узла.
 *
 * Доступ: /grouping-panel
 */
@Route("grouping-panel")
@PageTitle("Grouping Panel")
@PermitAll
public class GroupingPanelView extends VerticalLayout {

    /**
     * Условие пути: поле группировки + выбранное значение.
     */
    public record PathCondition(String field, String value) {
    }

    /**
     * Узел дерева значений: level = индекс поля в списке группировки,
     * path = все условия от корня до этого узла включительно.
     */
    public record GroupNode(int level, String value, List<PathCondition> path) {
    }

    private static final Map<String, String> AVAILABLE_FIELDS = new LinkedHashMap<>();
    static {
        AVAILABLE_FIELDS.put("code", "Код");
        AVAILABLE_FIELDS.put("name", "Наименование");
        AVAILABLE_FIELDS.put("unitOfMeasurement.shortCode", "Ед. изм.");
    }

    private static final Map<String, String> SORT_EXPRESSIONS =
            Map.of("code", "e.code", "name", "e.name", "unit", "e.unitOfMeasurement.shortCode");

    private final transient EntityManager entityManager;

    private final com.vaadin.flow.component.grid.Grid<Nomenclature> grid =
            new com.vaadin.flow.component.grid.Grid<>(Nomenclature.class, false);
    private ConfigurableFilterDataProvider<Nomenclature, Void, List<PathCondition>> gridFilter;

    private final MultiSelectListBox<String> availableList = new MultiSelectListBox<>();
    private final ListBox<String> chosenList = new ListBox<>();
    private final TreeGrid<GroupNode> tree = new TreeGrid<>();

    /** Текущий порядок полей группировки. */
    private final List<String> chosenFields = new ArrayList<>();

    public GroupingPanelView(EntityManager entityManager) {
        this.entityManager = entityManager;

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        add(new H2("Панель группировки + ленивый Grid"));
        add(new Paragraph(
                "Выберите поля группировки и постройте дерево. Клик по узлу дерева "
                        + "показывает в таблице записи, соответствующие всему пути узла. "
                        + "Данные и значения полей читаются из БД порциями."));

        HorizontalLayout main = new HorizontalLayout();
        main.setSizeFull();
        main.setSpacing(true);

        configureGrid();

        VerticalLayout panel = new VerticalLayout(
                new H4("Поля группировки"),
                availableList,
                new HorizontalLayout(addButton(), removeButton()),
                chosenList,
                new HorizontalLayout(upButton(), downButton(), buildButton(), showAllButton()));
        panel.setWidth("420px");
        panel.setFlexGrow(1.0, tree);

        panel.add(new H4("Дерево значений"), tree);
        tree.setSizeFull();

        main.add(grid, panel);
        main.setFlexGrow(1.0, grid);
        add(main);

        configurePanel();
    }

    // ------------------------------------------------------------------
    // Основной Grid (ленивый)
    // ------------------------------------------------------------------

    private void configureGrid() {
        grid.addColumn(Nomenclature::getCode)
                .setKey("code").setHeader("Код").setWidth("150px").setSortable(true);
        grid.addColumn(Nomenclature::getName)
                .setKey("name").setHeader("Наименование").setFlexGrow(1).setSortable(true);
        grid.addColumn(n -> n.getUnitOfMeasurement().getShortCode())
                .setKey("unit").setHeader("Ед. изм.").setWidth("120px");

        CallbackDataProvider<Nomenclature, List<PathCondition>> provider =
                new CallbackDataProvider<>(this::fetchItems, this::countItems);
        gridFilter = provider.withConfigurableFilter();
        grid.setDataProvider(gridFilter);
        grid.setSizeFull();
    }

    private Stream<Nomenclature> fetchItems(
            com.vaadin.flow.data.provider.Query<Nomenclature, List<PathCondition>> query) {
        List<PathCondition> filter = query.getFilter().orElse(List.of());
        String jpql = "SELECT e FROM Nomenclature e"
                + whereClause(filter) + orderByClause(query.getSortOrders());
        TypedQuery<Nomenclature> q = entityManager.createQuery(jpql, Nomenclature.class);
        bindParams(q, filter);
        q.setFirstResult(query.getOffset());
        q.setMaxResults(query.getLimit());
        return q.getResultStream();
    }

    private int countItems(
            com.vaadin.flow.data.provider.Query<Nomenclature, List<PathCondition>> query) {
        List<PathCondition> filter = query.getFilter().orElse(List.of());
        Query q = entityManager.createQuery(
                "SELECT COUNT(e) FROM Nomenclature e" + whereClause(filter), Long.class);
        bindParams(q, filter);
        return ((Long) q.getSingleResult()).intValue();
    }

    private String whereClause(List<PathCondition> filter) {
        if (filter.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(" WHERE ");
        for (int i = 0; i < filter.size(); i++) {
            if (i > 0) {
                sb.append(" AND ");
            }
            sb.append("e.").append(filter.get(i).field()).append(" = :c").append(i);
        }
        return sb.toString();
    }

    private void bindParams(Query query, List<PathCondition> filter) {
        for (int i = 0; i < filter.size(); i++) {
            query.setParameter("c" + i, filter.get(i).value());
        }
    }

    private String orderByClause(List<com.vaadin.flow.data.provider.QuerySortOrder> sortOrders) {
        if (sortOrders.isEmpty()) {
            return " ORDER BY e.code";
        }
        StringBuilder sb = new StringBuilder(" ORDER BY ");
        boolean first = true;
        for (var order : sortOrders) {
            String expression = SORT_EXPRESSIONS.get(order.getSorted());
            if (expression == null) {
                continue;
            }
            if (!first) {
                sb.append(", ");
            }
            sb.append(expression)
                    .append(order.getDirection() == SortDirection.DESCENDING ? " DESC" : " ASC");
            first = false;
        }
        if (first) {
            return " ORDER BY e.code";
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Панель группировки
    // ------------------------------------------------------------------

    private void configurePanel() {
        availableList.setItems(AVAILABLE_FIELDS.keySet());
        availableList.setRenderer(new TextRenderer<>(id -> AVAILABLE_FIELDS.get(id)));
        chosenList.setRenderer(new TextRenderer<>(id -> AVAILABLE_FIELDS.get(id)));
    }

    private Button addButton() {
        Button button = new Button("Добавить →", e -> {
            for (String id : availableList.getSelectedItems()) {
                if (!chosenFields.contains(id)) {
                    chosenFields.add(id);
                }
            }
            availableList.deselectAll();
            refreshChosenList();
        });
        return button;
    }

    private Button removeButton() {
        return new Button("← Убрать", e -> {
            String selected = chosenList.getValue();
            if (selected != null) {
                chosenFields.remove(selected);
                refreshChosenList();
            }
        });
    }

    private Button upButton() {
        return new Button("↑", e -> move(-1));
    }

    private Button downButton() {
        return new Button("↓", e -> move(1));
    }

    private void move(int delta) {
        String selected = chosenList.getValue();
        if (selected == null) {
            return;
        }
        int index = chosenFields.indexOf(selected);
        int target = index + delta;
        if (index >= 0 && target >= 0 && target < chosenFields.size()) {
            chosenFields.remove(index);
            chosenFields.add(target, selected);
            refreshChosenList();
        }
    }

    private Button buildButton() {
        Button button = new Button("Построить дерево");
        button.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_PRIMARY);
        button.addClickListener(e -> rebuildTree(List.copyOf(chosenFields)));
        return button;
    }

    private Button showAllButton() {
        return new Button("Показать все", e -> gridFilter.setFilter(null));
    }

    private void refreshChosenList() {
        chosenList.setItems(List.copyOf(chosenFields));
    }

    // ------------------------------------------------------------------
    // Ленивое дерево значений полей
    // ------------------------------------------------------------------

    private void rebuildTree(List<String> fields) {
        if (fields.isEmpty()) {
            tree.setDataProvider(DataProvider.ofItems(new GroupNode[0]));
            gridFilter.setFilter(null);
            return;
        }
        tree.setDataProvider(new DistinctValuesProvider(fields));

        tree.removeAllColumns();
        tree.addHierarchyColumn(node -> {
            String label = AVAILABLE_FIELDS.getOrDefault(fields.get(node.level()), node.level() + "");
            return label + ": " + node.value();
        }).setHeader("Значение").setFlexGrow(1);

        gridFilter.setFilter(null);
    }

    private long countDistinct(String field, List<PathCondition> conditions) {
        Query q = entityManager.createQuery(
                "SELECT COUNT(DISTINCT e." + field + ") FROM Nomenclature e" + whereClause(conditions),
                Long.class);
        bindParams(q, conditions);
        return (Long) q.getSingleResult();
    }

    private List<GroupNode> fetchDistinct(String field, List<PathCondition> conditions,
            int offset, int limit) {
        TypedQuery<String> q = entityManager.createQuery(
                "SELECT DISTINCT e." + field + " FROM Nomenclature e"
                        + whereClause(conditions) + " ORDER BY e." + field,
                String.class);
        bindParams(q, conditions);
        q.setFirstResult(offset);
        q.setMaxResults(limit);
        List<String> values = q.getResultList();
        List<GroupNode> nodes = new ArrayList<>(values.size());
        for (String value : values) {
            List<PathCondition> path = new ArrayList<>(conditions);
            path.add(new PathCondition(field, value));
            nodes.add(new GroupNode(conditions.size(), value, List.copyOf(path)));
        }
        return nodes;
    }

    private class DistinctValuesProvider
            extends AbstractBackEndHierarchicalDataProvider<GroupNode, Void> {

        private final List<String> fields;

        DistinctValuesProvider(List<String> fields) {
            this.fields = fields;
        }

        @Override
        public boolean hasChildren(GroupNode node) {
            return node.level() + 1 < fields.size();
        }

        @Override
        public int getChildCount(HierarchicalQuery<GroupNode, Void> query) {
            GroupNode parent = query.getParent();
            if (parent == null) {
                return (int) countDistinct(fields.get(0), List.of());
            }
            return (int) countDistinct(fields.get(parent.level() + 1), parent.path());
        }

        @Override
        protected Stream<GroupNode> fetchChildrenFromBackEnd(
                HierarchicalQuery<GroupNode, Void> query) {
            GroupNode parent = query.getParent();
            if (parent == null) {
                return fetchDistinct(fields.get(0), List.of(),
                        query.getOffset(), query.getLimit()).stream();
            }
            return fetchDistinct(fields.get(parent.level() + 1), parent.path(),
                    query.getOffset(), query.getLimit()).stream();
        }

        @Override
        public boolean isInMemory() {
            return false;
        }
    }
}
