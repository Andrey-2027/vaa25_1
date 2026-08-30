package org.ipro.reportstudio.query.constructor;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import org.ipro.reportstudio.query.JoinCondition;
import org.ipro.reportstudio.query.JoinLogicalOperator;
import org.ipro.reportstudio.query.QueryBuilderMetadataCatalog;
import org.ipro.reportstudio.query.VisualQueryDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Вкладка «Связи» в стиле 1С: сетка JOIN (№ | Таблица 1 | В. | Таблица 2 |
 * Псевдоним | Условие связи). Основной режим — связь по ассоциации Таблицы 1
 * (компилятор строит path-join, ON не нужен); галка «В.» делает связь левой.
 * Доп. режим — независимая связь двух таблиц с явным условием ON по полям.
 */
final class JoinsTab extends VerticalLayout {

    private enum AddMode { ASSOCIATED, INDEPENDENT }

    /** Строка сетки связей. */
    record JoinRow(int number, VisualQueryDefinition.Join join, String table1, String table2,
                   boolean left, String alias, String condition) { }

    private final QueryConstructorDraft draft;
    private final Runnable onChange;

    private final Grid<JoinRow> grid = new Grid<>();
    private final VerticalLayout addPanel = new VerticalLayout();
    private final VerticalLayout editorPanel = new VerticalLayout();

    // Панель добавления — общий режим
    private final ComboBox<AddMode> addMode = new ComboBox<>("Тип связи");
    // По ассоциации
    private final ComboBox<QueryConstructorDraft.TableRef> assocSource = new ComboBox<>("Таблица 1");
    private final ComboBox<QueryBuilderMetadataCatalog.Association> assocField = new ComboBox<>("Ассоциация");
    private final Checkbox assocAll = new Checkbox("Все строки таблицы 1 (левое)");
    private final TextField assocAlias = new TextField("Псевдоним");
    // Независимая
    private final ComboBox<QueryBuilderMetadataCatalog.Entity> indepTarget = new ComboBox<>("Сущность");
    private final Checkbox indepLeft = new Checkbox("Левое");
    private final TextField indepAlias = new TextField("Псевдоним");
    private final ComboBox<QueryConstructorDraft.TableRef> onLeftTable = new ComboBox<>("Таблица слева");
    private final ComboBox<String> onLeftField = new ComboBox<>("Поле слева");
    private final ComboBox<JoinCondition.Operator> onOperator = new ComboBox<>("Оператор");
    private final ComboBox<String> onRightField = new ComboBox<>("Поле справа");
    private final ComboBox<JoinLogicalOperator> onLogical = new ComboBox<>("Связать условия");
    private final VerticalLayout pendingOnConditions = new VerticalLayout();
    private final List<JoinCondition.Predicate> pendingPredicates = new ArrayList<>();
    private JoinLogicalOperator pendingLogical = JoinLogicalOperator.AND;
    private List<JoinCondition.Predicate> editPredicateList = new ArrayList<>();
    private HorizontalLayout associatedControls;
    private VerticalLayout independentBlock;

    // Редактор выбранной связи
    private final TextField editAlias = new TextField("Псевдоним");
    private final ComboBox<VisualQueryDefinition.JoinKind> editKind = new ComboBox<>("Тип");
    private final ComboBox<QueryConstructorDraft.TableRef> editLeftTable = new ComboBox<>("Таблица слева");
    private final ComboBox<String> editLeftField = new ComboBox<>("Поле слева");
    private final ComboBox<JoinCondition.Operator> editOperator = new ComboBox<>("Оператор");
    private final ComboBox<String> editRightField = new ComboBox<>("Поле справа");
    private final ComboBox<JoinLogicalOperator> editLogical = new ComboBox<>("Связать условия");
    private final VerticalLayout editConditions = new VerticalLayout();
    private VisualQueryDefinition.Join selectedJoin;

    JoinsTab(QueryConstructorDraft draft, Runnable onChange) {
        this.draft = draft;
        this.onChange = onChange == null ? () -> { } : onChange;

        setPadding(false);
        setSpacing(false);
        setWidthFull();
        setHeightFull();
        getStyle().set("min-height", "0");

        configureCombos();
        configureGrid();

        Button add = new Button("+ связь", event -> addPanel.setVisible(!addPanel.isVisible()));
        add.addThemeVariants(ButtonVariant.LUMO_SMALL);
        Button remove = new Button("Удалить выбранную", event -> removeSelected());
        remove.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        HorizontalLayout toolbar = new HorizontalLayout(add, remove);
        toolbar.setPadding(false);
        toolbar.setSpacing(true);

        buildAddPanel();
        addPanel.setVisible(false);
        buildEditorPanel();
        editorPanel.setVisible(false);

        VerticalLayout body = new VerticalLayout(toolbar, addPanel, grid, editorPanel);
        body.setPadding(false);
        body.setSpacing(false);
        body.setSizeFull();
        body.getStyle().set("min-height", "0").set("gap", "6px");
        body.setFlexGrow(1, grid);
        add(body);
    }

    private void configureCombos() {
        addMode.setItems(AddMode.values());
        addMode.setItemLabelGenerator(mode -> mode == AddMode.ASSOCIATED ? "По ассоциации" : "Независимая (условие ON)");
        addMode.setWidth("240px");
        addMode.setValue(AddMode.ASSOCIATED);
        addMode.addValueChangeListener(event -> switchAddControls());

        assocSource.setItemLabelGenerator(this::tableLabel);
        assocSource.setWidth("240px");
        assocSource.addValueChangeListener(event -> {
            assocField.setItems(availableAssociations(event.getValue()));
            assocAlias.setPlaceholder(assocField.getValue() == null ? "" : autoAlias(assocField.getValue()));
        });
        assocField.setItemLabelGenerator(association -> association.caption() + " (" + association.name() + ")");
        assocField.setWidth("220px");
        assocField.addValueChangeListener(event ->
                assocAlias.setPlaceholder(event.getValue() == null ? "" : autoAlias(event.getValue())));

        indepTarget.setItemLabelGenerator(QueryBuilderMetadataCatalog.Entity::entityName);
        indepTarget.setWidth("240px");
        indepTarget.addValueChangeListener(event -> {
            onRightField.setItems(qualifiedFields(event.getValue(), aliasOf(event.getValue())));
            indepAlias.setPlaceholder(event.getValue() == null ? "" : autoAliasEntity(event.getValue()));
            refreshPendingConditionValues();
        });
        onLeftTable.setItemLabelGenerator(this::tableLabel);
        onLeftTable.setWidth("240px");
        onLeftTable.addValueChangeListener(event -> refreshPendingConditionValues());
        onOperator.setItems(JoinCondition.Operator.values());
        onOperator.setItemLabelGenerator(operator -> operator == JoinCondition.Operator.EQ ? "=" : "<>");
        onOperator.setWidth("90px");
        onOperator.setValue(JoinCondition.Operator.EQ);
        onLogical.setItems(JoinLogicalOperator.values());
        onLogical.setItemLabelGenerator(op -> op == JoinLogicalOperator.AND ? "И" : "ИЛИ");
        onLogical.setWidth("70px");
        onLogical.setValue(JoinLogicalOperator.AND);
        onLogical.addValueChangeListener(event -> pendingLogical = event.getValue());

        editKind.setItems(VisualQueryDefinition.JoinKind.values());
        editKind.setItemLabelGenerator(kind -> kind == VisualQueryDefinition.JoinKind.LEFT ? "Левое" : "Внутреннее");
        editKind.setWidth("130px");
        editLeftTable.setItemLabelGenerator(this::tableLabel);
        editLeftTable.setWidth("240px");
        editLeftTable.addValueChangeListener(event -> refreshEditConditionValues());
        editOperator.setItems(JoinCondition.Operator.values());
        editOperator.setItemLabelGenerator(operator -> operator == JoinCondition.Operator.EQ ? "=" : "<>");
        editOperator.setWidth("90px");
        editOperator.setValue(JoinCondition.Operator.EQ);
        editLogical.setItems(JoinLogicalOperator.values());
        editLogical.setItemLabelGenerator(op -> op == JoinLogicalOperator.AND ? "И" : "ИЛИ");
        editLogical.setWidth("70px");
    }

    private void configureGrid() {
        grid.addColumn(JoinRow::number).setHeader("№").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(JoinRow::table1).setHeader("Таблица 1").setFlexGrow(1);
        grid.addColumn(row -> row.left() ? "✓" : "").setHeader("В.").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(JoinRow::table2).setHeader("Таблица 2").setFlexGrow(1);
        grid.addColumn(JoinRow::alias).setHeader("Псевдоним").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(JoinRow::condition).setHeader("Условие связи").setFlexGrow(2);
        grid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_NO_BORDER);
        grid.asSingleSelect().addValueChangeListener(event -> selectJoin(event.getValue() == null ? null : event.getValue().join()));
    }

    private void buildAddPanel() {
        HorizontalLayout associated = new HorizontalLayout(assocSource, assocField, assocAll, assocAlias, addButton(this::addAssociated));
        associatedControls = associated;
        HorizontalLayout independent = new HorizontalLayout(indepTarget, indepLeft, indepAlias);
        HorizontalLayout onControls = new HorizontalLayout(onLeftTable, onLeftField, onOperator, onRightField, onLogical, addButton(this::appendPendingCondition));
        for (HorizontalLayout row : List.of(associated, independent, onControls)) {
            row.setAlignItems(FlexComponent.Alignment.END);
            row.setSpacing(true);
            row.setWrap(true);
        }
        pendingOnConditions.setPadding(false);
        pendingOnConditions.setSpacing(false);
        Button addIndependent = new Button("Добавить независимую связь", event -> addIndependent());
        addIndependent.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_PRIMARY);
        independentBlock = new VerticalLayout(independent, onControls, pendingOnConditions, addIndependent);
        independentBlock.setPadding(false);
        independentBlock.setSpacing(false);
        independentBlock.getStyle().set("gap", "4px");
        addPanel.getStyle().set("border-bottom", "1px solid var(--lumo-contrast-10pct)")
                .set("padding-bottom", "6px");
        addPanel.add(new HorizontalLayout(addMode), associatedControls, independentBlock);
    }

    private void buildEditorPanel() {
        Button apply = new Button("Применить", event -> applyEditor());
        apply.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_PRIMARY);
        HorizontalLayout head = new HorizontalLayout(new Span("Редактирование связи"), editAlias, editKind, apply);
        head.setAlignItems(FlexComponent.Alignment.END);
        head.setSpacing(true);
        Button appendCondition = new Button("+ условие", event -> appendEditCondition());
        appendCondition.addThemeVariants(ButtonVariant.LUMO_SMALL);
        HorizontalLayout onControls = new HorizontalLayout(editLeftTable, editLeftField, editOperator, editRightField, editLogical, appendCondition);
        onControls.setAlignItems(FlexComponent.Alignment.END);
        onControls.setSpacing(true);
        onControls.setWrap(true);
        editConditions.setPadding(false);
        editConditions.setSpacing(false);
        editConditions.getStyle().set("gap", "2px");
        editorPanel.getStyle().set("border-top", "1px solid var(--lumo-contrast-10pct)")
                .set("padding-top", "6px");
        editorPanel.add(head, onControls, editConditions);
    }

    /** Доступ для тестов. */
    Grid<JoinRow> grid() { return grid; }

    void refreshFromDraft() {
        List<JoinRow> rows = new ArrayList<>();
        int number = 1;
        for (var join : draft.joins()) {
            var parent = join.independent() ? null : draft.entityForAlias(parentAlias(join));
            var target = draft.entityOfJoin(join);
            rows.add(new JoinRow(number++, join,
                    parent == null ? "—" : parent.entityName() + " (" + parentAlias(join) + ")",
                    target == null ? join.independent() ? join.sourcePath() : "?" : target.entityName(),
                    join.kind() == VisualQueryDefinition.JoinKind.LEFT,
                    join.alias(),
                    conditionText(join)));
        }
        grid.setItems(rows);
        assocSource.setItems(draft.tables());
        onLeftTable.setItems(draft.tables());
        editLeftTable.setItems(draft.tables());
        indepTarget.setItems(draft.roots().stream()
                .filter(entity -> !draft.isEntitySelected(entity.entityName()))
                .toList());
        if (selectedJoin != null && draft.joins().stream().noneMatch(j -> j.alias().equals(selectedJoin.alias()))) {
            selectedJoin = null;
            editorPanel.setVisible(false);
        }
        refreshPendingConditionValues();
        refreshEditConditionValues();
    }

    // === Добавление связи ===

    private void addAssociated() {
        var source = assocSource.getValue();
        var association = assocField.getValue();
        if (source == null || association == null) return;
        String alias = assocAlias.getValue() == null || assocAlias.getValue().isBlank()
                ? autoAlias(association) : assocAlias.getValue();
        var join = draft.addJoin(new VisualQueryDefinition.Join(source.alias(), association.name(), alias,
                assocAll.getValue() ? VisualQueryDefinition.JoinKind.LEFT : VisualQueryDefinition.JoinKind.INNER));
        assocField.clear();
        assocAlias.clear();
        assocAll.setValue(false);
        changed();
        if (join != null) selectJoin(join);
    }

    private void addIndependent() {
        var target = indepTarget.getValue();
        if (target == null || pendingPredicates.isEmpty()) return;
        String alias = indepAlias.getValue() == null || indepAlias.getValue().isBlank()
                ? autoAliasEntity(target) : indepAlias.getValue();
        JoinCondition on = pendingPredicates.size() == 1 ? pendingPredicates.get(0)
                : new JoinCondition.Group(pendingLogical,
                        pendingPredicates.stream().map(predicate -> (JoinCondition) predicate).toList());
        var join = draft.addJoin(new VisualQueryDefinition.Join(null, target.entityName(), alias,
                indepLeft.getValue() ? VisualQueryDefinition.JoinKind.LEFT : VisualQueryDefinition.JoinKind.INNER, on));
        pendingPredicates.clear();
        pendingOnConditions.removeAll();
        indepTarget.clear();
        indepAlias.clear();
        indepLeft.setValue(false);
        changed();
        if (join != null) selectJoin(join);
    }

    private void appendPendingCondition() {
        String left = onLeftField.getValue();
        String right = onRightField.getValue();
        if (left == null || right == null) return;
        var predicate = new JoinCondition.Predicate(left, onOperator.getValue(), right);
        pendingPredicates.add(predicate);
        renderPendingCondition(predicate);
        onLeftField.clear();
        onRightField.clear();
    }

    private void renderPendingCondition(JoinCondition.Predicate predicate) {
        Span label = new Span(conditionOf(predicate));
        Button remove = smallRemove(event -> {
            pendingPredicates.remove(predicate);
            pendingOnConditions.remove(label.getParent().orElse(null));
        });
        HorizontalLayout row = new HorizontalLayout(label, remove);
        row.setPadding(false);
        row.setSpacing(true);
        pendingOnConditions.add(row);
    }

    private static List<String> qualifiedFields(QueryBuilderMetadataCatalog.Entity entity, String alias) {
        if (entity == null) return List.of();
        return entity.fields().stream().map(f -> alias + "." + f.name()).toList();
    }

    private void refreshPendingConditionValues() {
        var table = onLeftTable.getValue();
        onLeftField.setItems(qualifiedFields(table == null ? null : table.entity(), table == null ? "" : table.alias()));
        var target = indepTarget.getValue();
        onRightField.setItems(qualifiedFields(target, aliasOf(target)));
    }

    private void switchAddControls() {
        boolean associated = addMode.getValue() == AddMode.ASSOCIATED;
        associatedControls.setVisible(associated);
        independentBlock.setVisible(!associated);
    }

    // === Редактирование выбранной связи ===

    private void selectJoin(VisualQueryDefinition.Join join) {
        selectedJoin = join;
        editorPanel.setVisible(join != null);
        if (join == null) return;
        editAlias.setValue(join.alias());
        editKind.setValue(join.kind());
        editPredicateList = new ArrayList<>(predicatesOf(join.on()));
        editConditions.removeAll();
        for (JoinCondition.Predicate predicate : editPredicateList) renderEditCondition(predicate);
        refreshEditConditionValues();
    }

    private void refreshEditConditionValues() {
        var table = editLeftTable.getValue();
        editLeftField.setItems(table == null ? List.of()
                : table.entity().fields().stream().map(f -> table.alias() + "." + f.name()).toList());
        var join = selectedJoin;
        var target = join == null ? null : draft.entityOfJoin(join);
        editRightField.setItems(target == null ? List.of()
                : target.fields().stream().map(f -> join.alias() + "." + f.name()).toList());
    }

    private void appendEditCondition() {
        String left = editLeftField.getValue();
        String right = editRightField.getValue();
        if (selectedJoin == null || left == null || right == null) return;
        editPredicateList.add(new JoinCondition.Predicate(left, editOperator.getValue(), right));
        rerenderEditConditions();
    }

    private void renderEditCondition(JoinCondition.Predicate predicate) {
        Span label = new Span(conditionOf(predicate));
        Button remove = smallRemove(event -> {
            editPredicateList.remove(predicate);
            rerenderEditConditions();
        });
        HorizontalLayout row = new HorizontalLayout(label, remove);
        row.setPadding(false);
        row.setSpacing(true);
        editConditions.add(row);
    }

    private void rerenderEditConditions() {
        editConditions.removeAll();
        for (JoinCondition.Predicate predicate : editPredicateList) renderEditCondition(predicate);
    }

    private void applyEditor() {
        var join = selectedJoin;
        if (join == null) return;
        String alias = editAlias.getValue();
        JoinCondition on;
        if (join.independent()) {
            on = editPredicateList.isEmpty() ? null
                    : editPredicateList.size() == 1 ? editPredicateList.get(0)
                    : new JoinCondition.Group(editLogical.getValue(),
                            editPredicateList.stream().map(predicate -> (JoinCondition) predicate).toList());
        } else {
            on = null;
        }
        draft.updateJoin(join, alias, editKind.getValue(), on);
        changed();
    }

    private void removeSelected() {
        var row = grid.asSingleSelect().getValue();
        if (row == null) return;
        draft.removeTable(row.join().alias());
        changed();
    }

    // === Отображение ===

    private String conditionText(VisualQueryDefinition.Join join) {
        if (join.independent()) {
            List<JoinCondition.Predicate> predicates = predicatesOf(join.on());
            if (predicates.isEmpty()) return "(условие не задано)";
            String op = join.on() instanceof JoinCondition.Group group && group.operator() == JoinLogicalOperator.OR
                    ? " ИЛИ " : " И ";
            return String.join(op, predicates.stream().map(this::conditionOf).toList());
        }
        var parent = draft.entityForAlias(parentAlias(join));
        var target = draft.entityOfJoin(join);
        String left = (parent == null ? parentAlias(join) : parent.entityName()) + "." + join.sourcePath();
        String right = (target == null ? join.alias() : target.entityName()) + " (ключ)";
        return left + " = " + right;
    }

    private String conditionOf(JoinCondition.Predicate predicate) {
        return predicate.leftPath() + (predicate.operator() == JoinCondition.Operator.EQ ? " = " : " <> ")
                + predicate.rightPath();
    }

    private static List<JoinCondition.Predicate> predicatesOf(JoinCondition on) {
        if (on == null) return List.of();
        if (on instanceof JoinCondition.Predicate predicate) return List.of(predicate);
        JoinCondition.Group group = (JoinCondition.Group) on;
        return group.children().stream()
                .filter(child -> child instanceof JoinCondition.Predicate)
                .map(child -> (JoinCondition.Predicate) child)
                .toList();
    }

    private String tableLabel(QueryConstructorDraft.TableRef table) {
        return table.entity().entityName() + (table.root() ? " (FROM)" : " (" + table.alias() + ")");
    }

    private List<QueryBuilderMetadataCatalog.Association> availableAssociations(QueryConstructorDraft.TableRef table) {
        if (table == null) return List.of();
        return table.entity().associations().stream()
                .filter(association -> association.targetType() != null)
                .filter(association -> draft.entityByType(association.targetType()) != null)
                .filter(association -> draft.joins().stream().noneMatch(join ->
                        Objects.equals(parentAlias(join), table.alias()) && join.sourcePath().equals(association.name())))
                .toList();
    }

    private static String autoAlias(QueryBuilderMetadataCatalog.Association association) {
        return lowerCamel(association.targetType() == null ? association.name() : association.targetType().getSimpleName());
    }

    private static String autoAliasEntity(QueryBuilderMetadataCatalog.Entity entity) {
        return lowerCamel(entity.entityName());
    }

    private static String lowerCamel(String name) {
        if (name == null || name.isBlank()) return "t";
        return name.substring(0, 1).toLowerCase(java.util.Locale.ROOT) + name.substring(1);
    }

    private String parentAlias(VisualQueryDefinition.Join join) {
        return join.parentAlias() == null || join.parentAlias().isBlank() ? draft.rootAlias() : join.parentAlias();
    }

    private String aliasOf(QueryBuilderMetadataCatalog.Entity entity) {
        return entity == null ? "" : lowerCamel(entity.entityName());
    }

    private Button addButton(Runnable action) {
        Button button = new Button("+", event -> action.run());
        button.addThemeVariants(ButtonVariant.LUMO_SMALL);
        return button;
    }

    private Button smallRemove(com.vaadin.flow.component.ComponentEventListener<com.vaadin.flow.component.ClickEvent<Button>> listener) {
        Button button = new Button("×", listener);
        button.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        return button;
    }

    private void changed() {
        onChange.run();
    }
}
