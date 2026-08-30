package org.ipro.reportstudio.query.constructor;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.treegrid.TreeGrid;
import org.ipro.reportstudio.query.JoinLogicalOperator;
import org.ipro.reportstudio.query.QueryBuilderMetadataCatalog;
import org.ipro.reportstudio.query.VisualQueryDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * Вкладка «Группировка» в стиле 1С: слева выбранные поля и дерево всех полей
 * выбранных таблиц (взять можно любое поле), справа сверху «Поле группировки»,
 * справа снизу «Суммируемое поле | Функция». Как в 1С: при наличии агрегатов
 * все не-агрегатные поля SELECT автоматически попадают в GROUP BY; агрегат
 * замещает обычное поле SELECT, сохраняя его псевдоним.
 *
 * <p>Внизу — «Условия на итоги (HAVING)»: в JPQL HAVING привязан к GROUP BY,
 * поэтому условия по агрегатам живут на этой вкладке, а не отдельной.</p>
 */
final class GroupingTab extends VerticalLayout {

    private enum Target { GROUPING, AGGREGATES }

    /** Строка «Поле группировки»: явная или авто (от не-агрегатного SELECT-поля). */
    record GroupRow(String path, String display, boolean auto) { }

    private final QueryConstructorDraft draft;
    private final Runnable onChange;
    private Target activeTarget = Target.GROUPING;

    private final Grid<VisualQueryDefinition.SelectField> selectedFields = new Grid<>();
    private final TreeGrid<ConstructorTreeNode> allFields = new TreeGrid<>();
    private final Grid<GroupRow> groupingGrid = new Grid<>();
    private final Grid<VisualQueryDefinition.Aggregate> aggregatesGrid = new Grid<>();
    private final ComboBox<JoinLogicalOperator> havingLogical = new ComboBox<>();
    private final Grid<VisualQueryDefinition.HavingCondition> havingGrid = new Grid<>();
    private final Span havingHint = new Span();
    /** Корни дерева полей последнего refreshFromDraft (для тестов). */
    private List<ConstructorTreeNode> fieldRoots = List.of();

    GroupingTab(QueryConstructorDraft draft, Runnable onChange) {
        this.draft = draft;
        this.onChange = onChange == null ? () -> { } : onChange;

        setPadding(false);
        setSpacing(false);
        setWidthFull();
        setHeightFull();
        getStyle().set("min-height", "0");

        configureLeft();
        configureGrouping();
        configureAggregates();
        configureHaving();

        VerticalLayout left = new VerticalLayout(new Span("Поля"), selectedFields, new Span("Все поля"), allFields);
        left.setPadding(false);
        left.setSpacing(false);
        left.setSizeFull();
        left.getStyle().set("min-height", "0").set("min-width", "0").set("gap", "4px");
        left.setFlexGrow(0.4, selectedFields);
        left.setFlexGrow(1, allFields);

        VerticalLayout groupingPanel = new VerticalLayout(new Span("Поле группировки"), groupingGrid);
        groupingPanel.setPadding(false);
        groupingPanel.setSpacing(false);
        groupingPanel.setSizeFull();
        groupingPanel.getStyle().set("min-height", "0").set("gap", "4px");
        groupingPanel.setFlexGrow(1, groupingGrid);

        VerticalLayout aggregatesPanel = new VerticalLayout(new Span("Суммируемое поле"), aggregatesGrid);
        aggregatesPanel.setPadding(false);
        aggregatesPanel.setSpacing(false);
        aggregatesPanel.setSizeFull();
        aggregatesPanel.getStyle().set("min-height", "0").set("gap", "4px");
        aggregatesPanel.setFlexGrow(1, aggregatesGrid);

        HorizontalLayout right = new HorizontalLayout(groupingPanel, aggregatesPanel);
        right.setPadding(false);
        right.setSpacing(false);
        right.setSizeFull();
        right.getStyle().set("min-height", "0");
        right.setFlexGrow(0.55, groupingPanel);
        right.setFlexGrow(1, aggregatesPanel);

        VerticalLayout buttons = transferColumns();

        HorizontalLayout content = new HorizontalLayout(left, buttons, right);
        content.setPadding(false);
        content.setSpacing(false);
        content.setWidthFull();
        content.setHeightFull();
        content.setFlexGrow(1, left, right);
        content.getStyle().set("min-height", "0");

        VerticalLayout body = new VerticalLayout(content, havingPanel());
        body.setPadding(false);
        body.setSpacing(false);
        body.setSizeFull();
        body.getStyle().set("min-height", "0").set("gap", "4px");
        body.setFlexGrow(1, content);
        add(body);
    }

    /** Доступ для тестов. */
    List<ConstructorTreeNode> fieldRoots() { return fieldRoots; }

    TreeGrid<ConstructorTreeNode> allFields() { return allFields; }

    Grid<GroupRow> groupingGrid() { return groupingGrid; }

    Grid<VisualQueryDefinition.Aggregate> aggregatesGrid() { return aggregatesGrid; }

    Grid<VisualQueryDefinition.HavingCondition> havingGrid() { return havingGrid; }

    void refreshFromDraft() {
        selectedFields.setItems(draft.selections());
        fieldRoots = CatalogTreeModel.tableRoots(draft.tables(), draft.roots());
        allFields.setItems(fieldRoots, ConstructorTreeNode::children);
        allFields.expand(fieldRoots);
        List<GroupRow> rows = new ArrayList<>();
        for (String path : draft.effectiveGrouping()) {
            rows.add(new GroupRow(path, draft.displayPath(path), draft.isAutoGrouping(path)));
        }
        groupingGrid.setItems(rows);
        aggregatesGrid.setItems(draft.aggregates());

        havingGrid.setItems(draft.havingConditions());
        havingLogical.setValue(draft.havingOperator());
        havingHint.setText(draft.aggregates().isEmpty()
                ? "Доступно после добавления агрегатов в «Суммируемое поле»."
                : "");
    }

    // === Настройка компонентов ===

    private void configureLeft() {
        selectedFields.addColumn(field -> draft.displayPath(field.path())).setHeader("Поле").setFlexGrow(1);
        selectedFields.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_NO_BORDER);
        selectedFields.setMaxHeight("30%");

        allFields.addHierarchyColumn(ConstructorTreeNode::caption).setHeader("Сущности и поля")
                .setFlexGrow(1).setResizable(true);
        allFields.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_NO_BORDER);
        allFields.getStyle().set("min-height", "0");
    }

    private void configureGrouping() {
        groupingGrid.addColumn(row -> row.display() + (row.auto() ? "  (авто)" : ""))
                .setHeader("Поле группировки").setFlexGrow(1);
        groupingGrid.addComponentColumn(this::groupingRemoveButton).setAutoWidth(true).setFlexGrow(0);
        groupingGrid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_NO_BORDER);
        groupingGrid.addFocusListener(event -> activeTarget = Target.GROUPING);
    }

    private void configureAggregates() {
        aggregatesGrid.addColumn(aggregate -> draft.displayPath(aggregate.path()))
                .setHeader("Суммируемое поле").setFlexGrow(1);
        aggregatesGrid.addComponentColumn(this::functionCombo).setHeader("Функция").setAutoWidth(true).setFlexGrow(0);
        aggregatesGrid.addComponentColumn(this::aggregateRemoveButton).setAutoWidth(true).setFlexGrow(0);
        aggregatesGrid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_NO_BORDER);
        aggregatesGrid.addFocusListener(event -> activeTarget = Target.AGGREGATES);
    }

    private void configureHaving() {
        havingLogical.setItems(JoinLogicalOperator.values());
        havingLogical.setItemLabelGenerator(operator -> operator == JoinLogicalOperator.AND ? "И" : "ИЛИ");
        havingLogical.setWidth("72px");
        havingLogical.addValueChangeListener(event -> {
            if (event.getValue() != null && event.getValue() != draft.havingOperator()) {
                draft.setHavingOperator(event.getValue());
                changed();
            }
        });
        havingGrid.addComponentColumn(this::havingAggregateCombo).setHeader("Агрегат").setAutoWidth(true).setFlexGrow(0);
        havingGrid.addComponentColumn(this::havingOperatorCombo).setHeader("Оператор").setAutoWidth(true).setFlexGrow(0);
        havingGrid.addComponentColumn(this::havingValueField).setHeader("Значение").setFlexGrow(1);
        havingGrid.addComponentColumn(this::havingRemoveButton).setAutoWidth(true).setFlexGrow(0);
        havingGrid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_NO_BORDER);
        havingHint.getStyle().set("color", "var(--lumo-secondary-text-color)").set("font-size", "var(--lumo-font-size-s)");
    }

    private VerticalLayout havingPanel() {
        Span title = new Span("Условия на итоги (HAVING)");
        title.getStyle().set("font-weight", "600").set("font-size", "var(--lumo-font-size-s)");
        Button add = new Button("+ условие", event -> addHavingCondition());
        add.addThemeVariants(ButtonVariant.LUMO_SMALL);

        HorizontalLayout header = new HorizontalLayout(title, havingLogical, add, havingHint);
        header.setPadding(false);
        header.setSpacing(false);
        header.setAlignItems(FlexComponent.Alignment.CENTER);
        header.getStyle().set("gap", "8px");

        VerticalLayout panel = new VerticalLayout(header, havingGrid);
        panel.setPadding(false);
        panel.setSpacing(false);
        panel.setWidthFull();
        panel.getStyle().set("min-height", "0").set("gap", "4px").set("max-height", "30%").set("flex-shrink", "0");
        panel.setFlexGrow(1, havingGrid);
        return panel;
    }

    private ComboBox<AggregateFunction> functionCombo(VisualQueryDefinition.Aggregate aggregate) {
        ComboBox<AggregateFunction> combo = new ComboBox<>();
        combo.setItems(allowedFunctions(aggregate));
        combo.setItemLabelGenerator(AggregateFunction::caption);
        combo.setWidth("150px");
        fromCode(aggregate.function()).ifPresent(combo::setValue);
        combo.addValueChangeListener(event -> {
            if (event.getValue() != null && !event.getValue().code().equals(aggregate.function())) {
                draft.replaceAggregateFunction(aggregate, event.getValue().code());
                changed();
            }
        });
        return combo;
    }

    private List<AggregateFunction> allowedFunctions(VisualQueryDefinition.Aggregate aggregate) {
        var entity = draft.entityForAlias(QueryConstructorDraft.firstSegment(aggregate.path()));
        QueryBuilderMetadataCatalog.Field field = null;
        if (entity != null) {
            String name = aggregate.path().contains(".")
                    ? aggregate.path().substring(aggregate.path().indexOf('.') + 1) : aggregate.path();
            field = entity.fields().stream().filter(f -> f.name().equals(name)).findFirst()
                    .orElseGet(() -> entity.associations().stream().filter(a -> a.name().equals(name)).findFirst()
                            .map(a -> new QueryBuilderMetadataCatalog.Field(a.name(), a.caption(),
                                    a.targetType() == null ? Object.class : a.targetType(), false))
                            .orElse(null));
        }
        return AggregateFunction.allowedFor(field);
    }

    private Button groupingRemoveButton(GroupRow row) {
        Button button = new Button("×");
        button.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        button.getElement().setAttribute("title", "Убрать из группировки");
        button.setEnabled(!row.auto());
        button.addClickListener(event -> {
            draft.removeGrouping(row.path());
            changed();
        });
        return button;
    }

    private Button aggregateRemoveButton(VisualQueryDefinition.Aggregate aggregate) {
        Button button = new Button("×");
        button.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        button.getElement().setAttribute("title", "Убрать агрегат (поле вернётся в SELECT, если было выбрано)");
        button.addClickListener(event -> {
            draft.removeAggregate(aggregate);
            changed();
        });
        return button;
    }

    /**
     * Две колонки стрелок, как в 1С: верхняя — «Поле группировки», нижняя —
     * «Суммируемое поле» (агрегаты). Активная цель определяется колонкой,
     * а не фокусом грида.
     */
    private VerticalLayout transferColumns() {
        HorizontalLayout grouping = transferStack(
                transfer(">", this::moveToGrouping, "В «Поле группировки»"),
                transfer(">>", this::moveAllToGrouping, "Все выбранные поля в «Поле группировки»"),
                transfer("<", this::removeFromGrouping, "Убрать из «Поле группировки»"),
                transfer("<<", this::clearGroupingList, "Очистить «Поле группировки»"));
        HorizontalLayout aggregates = transferStack(
                transfer(">", this::moveToAggregates, "В «Суммируемое поле» (агрегат Сумма)"),
                transfer(">>", this::moveAllToAggregates, "Все выбранные поля в «Суммируемое поле»"),
                transfer("<", this::removeFromAggregates, "Убрать агрегат"),
                transfer("<<", this::clearAggregates, "Убрать все агрегаты"));
        VerticalLayout column = new VerticalLayout(grouping, aggregates);
        column.setPadding(false);
        column.setSpacing(true);
        column.setWidth("72px");
        column.getStyle().set("min-height", "0");
        column.setAlignItems(FlexComponent.Alignment.CENTER);
        return column;
    }

    private HorizontalLayout transferStack(Button... buttons) {
        HorizontalLayout stack = new HorizontalLayout(buttons);
        stack.setPadding(false);
        stack.setSpacing(false);
        stack.setWidth("72px");
        stack.getStyle().set("flex-direction", "column");
        stack.setAlignItems(FlexComponent.Alignment.CENTER);
        return stack;
    }

    private Button transfer(String caption, Runnable action, String tooltip) {
        Button button = new Button(caption, event -> action.run());
        button.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        button.getElement().setAttribute("title", tooltip);
        button.setWidthFull();
        return button;
    }

    // === Перенос ===

    void moveToActive() { moveTo(activeTarget); }

    void moveAllToActive() { moveAllTo(activeTarget); }

    void removeFromActive() { removeFrom(activeTarget); }

    void clearActive() { clearTarget(activeTarget); }

    // === Явные цели стрелок: верхняя колонка — группировка, нижняя — агрегаты ===

    void moveToGrouping() { moveTo(Target.GROUPING); }

    void moveAllToGrouping() { moveAllTo(Target.GROUPING); }

    void removeFromGrouping() { removeFrom(Target.GROUPING); }

    void clearGroupingList() { clearTarget(Target.GROUPING); }

    void moveToAggregates() { moveTo(Target.AGGREGATES); }

    void moveAllToAggregates() { moveAllTo(Target.AGGREGATES); }

    void removeFromAggregates() { removeFrom(Target.AGGREGATES); }

    void clearAggregates() { clearTarget(Target.AGGREGATES); }

    private void moveTo(Target target) {
        ConstructorTreeNode node = allFields.asSingleSelect().getValue();
        var selectedField = selectedFields.asSingleSelect().getValue();
        if (node != null && node.kind() == ConstructorTreeNode.Kind.PROPERTY) {
            applyTo(target, fieldPath(node));
            changed();
            return;
        }
        if (selectedField != null) {
            applyTo(target, selectedField.path());
            changed();
            return;
        }
        if (node != null && node.kind() == ConstructorTreeNode.Kind.ENTITY) {
            var base = node.ownerEntity().entity();
            for (var field : node.entity().fields()) {
                applyTo(target, fieldPathOf(base, List.of(), field.name()));
            }
            changed();
        }
    }

    private void moveAllTo(Target target) {
        for (var field : draft.selections()) {
            applyTo(target, field.path());
        }
        changed();
    }

    private void removeFrom(Target target) {
        if (target == Target.GROUPING) {
            GroupRow row = groupingGrid.asSingleSelect().getValue();
            if (row != null && !row.auto()) {
                draft.removeGrouping(row.path());
                changed();
            }
        } else {
            var aggregate = aggregatesGrid.asSingleSelect().getValue();
            if (aggregate != null) {
                draft.removeAggregate(aggregate);
                changed();
            }
        }
    }

    private void clearTarget(Target target) {
        if (target == Target.GROUPING) {
            draft.clearGrouping();
        } else {
            draft.clearAggregates();
        }
        changed();
    }

    private void applyTo(Target target, String path) {
        if (path == null) return;
        if (target == Target.GROUPING) {
            draft.addGrouping(path);
        } else {
            draft.addAggregate(AggregateFunction.SUM.code(), path, null);
        }
    }

    /** Путь поля узла дерева; ассоциативная цепочка обеспечивается черновиком. */
    private String fieldPath(ConstructorTreeNode node) {
        if (node.kind() != ConstructorTreeNode.Kind.PROPERTY) return null;
        var base = node.ownerEntity().entity();
        return fieldPathOf(base, node.chainToOwner(), node.field().name());
    }

    /** Путь без создания JOIN (для группировки/агрегатов цепочка JOIN нужна так же, как для полей). */
    private String fieldPathOf(QueryBuilderMetadataCatalog.Entity base,
                               List<QueryBuilderMetadataCatalog.Association> chain, String fieldName) {
        if (fieldName == null) return null;
        String alias = draft.ensureChain(base, chain);
        return alias + "." + fieldName;
    }

    // === Условия на итоги (HAVING) ===

    void addHavingCondition() {
        if (draft.aggregates().isEmpty()) return;
        draft.addHavingCondition(draft.aggregates().get(0).resultName(),
                VisualQueryDefinition.HavingOperator.GT, new VisualQueryDefinition.HavingNumber(0));
        changed();
    }

    private ComboBox<String> havingAggregateCombo(VisualQueryDefinition.HavingCondition condition) {
        ComboBox<String> combo = new ComboBox<>();
        combo.setItems(draft.aggregates().stream().map(VisualQueryDefinition.Aggregate::resultName).toList());
        combo.setWidth("150px");
        combo.setValue(condition.aggregateAlias());
        combo.addValueChangeListener(event -> {
            if (event.getValue() != null && !event.getValue().equals(condition.aggregateAlias())) {
                draft.replaceHavingCondition(condition, new VisualQueryDefinition.HavingCondition(
                        event.getValue(), condition.operator(), condition.value()));
                changed();
            }
        });
        return combo;
    }

    private ComboBox<VisualQueryDefinition.HavingOperator> havingOperatorCombo(VisualQueryDefinition.HavingCondition condition) {
        ComboBox<VisualQueryDefinition.HavingOperator> combo = new ComboBox<>();
        combo.setItems(VisualQueryDefinition.HavingOperator.values());
        combo.setItemLabelGenerator(VisualQueryDefinition.HavingOperator::symbol);
        combo.setWidth("80px");
        combo.setValue(condition.operator());
        combo.addValueChangeListener(event -> {
            if (event.getValue() != null && event.getValue() != condition.operator()) {
                draft.replaceHavingCondition(condition, new VisualQueryDefinition.HavingCondition(
                        condition.aggregateAlias(), event.getValue(), condition.value()));
                changed();
            }
        });
        return combo;
    }

    private TextField havingValueField(VisualQueryDefinition.HavingCondition condition) {
        TextField field = new TextField();
        field.setPlaceholder("число или :параметр");
        field.setWidth("160px");
        field.setValue(displayValue(condition.value()));
        field.addValueChangeListener(event -> {
            VisualQueryDefinition.HavingValue parsed = parseValue(event.getValue());
            if (parsed == null || sameValue(parsed, condition.value())) return;
            draft.replaceHavingCondition(condition, new VisualQueryDefinition.HavingCondition(
                    condition.aggregateAlias(), condition.operator(), parsed));
            changed();
        });
        return field;
    }

    private Button havingRemoveButton(VisualQueryDefinition.HavingCondition condition) {
        Button button = new Button("×");
        button.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        button.getElement().setAttribute("title", "Убрать условие");
        button.addClickListener(event -> {
            draft.removeHavingCondition(condition);
            changed();
        });
        return button;
    }

    private static String displayValue(VisualQueryDefinition.HavingValue value) {
        if (value instanceof VisualQueryDefinition.HavingNumber number) return formatNumber(number.value());
        if (value instanceof VisualQueryDefinition.HavingParamRef parameter) return ":" + parameter.name();
        if (value instanceof VisualQueryDefinition.HavingAggregateRef aggregate) return aggregate.alias();
        return "";
    }

    private static VisualQueryDefinition.HavingValue parseValue(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.trim();
        if (trimmed.startsWith(":")) {
            String name = trimmed.substring(1);
            return name.matches("[A-Za-z_][A-Za-z0-9_]*") ? new VisualQueryDefinition.HavingParamRef(name) : null;
        }
        try {
            return new VisualQueryDefinition.HavingNumber(Double.parseDouble(trimmed.replace(',', '.')));
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static boolean sameValue(VisualQueryDefinition.HavingValue a, VisualQueryDefinition.HavingValue b) {
        if (a instanceof VisualQueryDefinition.HavingNumber number
                && b instanceof VisualQueryDefinition.HavingNumber other) {
            return number.value() == other.value();
        }
        if (a instanceof VisualQueryDefinition.HavingParamRef parameter
                && b instanceof VisualQueryDefinition.HavingParamRef otherParameter) {
            return parameter.name().equals(otherParameter.name());
        }
        return false;
    }

    private static String formatNumber(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    // === Утилиты ===

    private static java.util.Optional<AggregateFunction> fromCode(String code) {
        if (code == null) return java.util.Optional.empty();
        try {
            return java.util.Optional.of(AggregateFunction.valueOf(code));
        } catch (IllegalArgumentException unknown) {
            return java.util.Optional.empty();
        }
    }

    private void changed() {
        onChange.run();
    }
}
