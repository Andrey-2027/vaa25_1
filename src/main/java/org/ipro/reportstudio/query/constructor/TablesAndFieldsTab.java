package org.ipro.reportstudio.query.constructor;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.FlexLayout;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.textfield.TextFieldVariant;
import com.vaadin.flow.component.treegrid.TreeGrid;
import org.ipro.reportstudio.query.VisualQueryDefinition;
import org.ipro.reportstudio.query.VisualQueryTextParser;

import java.util.List;

/**
 * Вкладка «Таблицы и поля» конструктора в стиле 1С: слева «База данных»
 * (дерево сущностей каталога), в центре выбранные таблицы (первая — FROM),
 * справа выбранные поля. Кнопки переноса `>` `>>` `<` `<<` между панелями,
 * двойной клик — быстрый перенос. Поле, взятое сквозь ассоциацию, автоматически
 * создаёт промежуточные JOIN; двойной клик по самой ассоциации берёт её
 * сущностное поле (ссылку) в SELECT — как в 1С. Alias таблиц и псевдонимы
 * полей правятся прямо в колонках.
 */
final class TablesAndFieldsTab extends VerticalLayout {

    private final QueryConstructorDraft draft;
    private final Runnable onChange;

    private final TreeGrid<ConstructorTreeNode> databaseTree = new TreeGrid<>();
    private final TreeGrid<ConstructorTreeNode> tablesTree = new TreeGrid<>();
    private final Grid<VisualQueryDefinition.SelectField> fieldsGrid = new Grid<>();
    /** Вычисляемые поля (CASE/арифметика/функции) — восстановленные из текста, без редактора. */
    private final Grid<VisualQueryDefinition.Expression> expressionsGrid = new Grid<>();
    /** Корни деревьев последнего refreshFromDraft (для тестов — TreeGrid не даёт list data view). */
    private List<ConstructorTreeNode> databaseRoots = List.of();
    private List<ConstructorTreeNode> tableRoots = List.of();

    TablesAndFieldsTab(QueryConstructorDraft draft, Runnable onChange) {
        this.draft = draft;
        this.onChange = onChange == null ? () -> { } : onChange;

        setPadding(false);
        setSpacing(false);
        setWidthFull();
        setHeightFull();
        getStyle().set("min-height", "0");

        databaseTree.addHierarchyColumn(ConstructorTreeNode::caption).setHeader("База данных")
                .setFlexGrow(1).setResizable(true);
        databaseTree.addColumn(this::kindLabel).setHeader("Вид").setAutoWidth(true).setFlexGrow(0);
        databaseTree.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_NO_BORDER);
        databaseTree.addItemDoubleClickListener(event -> addNode(event.getItem()));

        tablesTree.addHierarchyColumn(ConstructorTreeNode::caption).setHeader("Таблицы")
                .setFlexGrow(1).setResizable(true);
        tablesTree.addComponentColumn(this::aliasEditor).setHeader("Alias").setAutoWidth(true).setFlexGrow(0);
        tablesTree.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_NO_BORDER);
        tablesTree.addItemDoubleClickListener(event -> {
            if (event.getItem().kind() == ConstructorTreeNode.Kind.PROPERTY) {
                addNode(event.getItem());
            }
        });

        fieldsGrid.addColumn(field -> draft.displayPath(field.path())).setHeader("Поле").setFlexGrow(1);
        fieldsGrid.addComponentColumn(this::aliasFieldEditor).setHeader("Псевдоним").setAutoWidth(true).setFlexGrow(0);
        fieldsGrid.addComponentColumn(this::removeButton).setAutoWidth(true).setFlexGrow(0);
        fieldsGrid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_NO_BORDER);

        Span computedTitle = new Span("Вычисляемые поля (из текста)");
        computedTitle.getStyle().set("font-weight", "600").set("font-size", "var(--lumo-font-size-s)");
        Button addComputed = new Button("Добавить…", event -> openComputedEditor(null));
        addComputed.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_SMALL,
                com.vaadin.flow.component.button.ButtonVariant.LUMO_TERTIARY);
        addComputed.getElement().setAttribute("title", "Добавить вычисляемое поле (арифметика, функции, CASE)");
        HorizontalLayout computedHeader = new HorizontalLayout(computedTitle, addComputed);
        computedHeader.setPadding(false);
        computedHeader.setSpacing(false);
        computedHeader.setAlignItems(FlexComponent.Alignment.CENTER);
        computedHeader.setWidthFull();
        computedHeader.setJustifyContentMode(FlexComponent.JustifyContentMode.BETWEEN);
        expressionsGrid.addItemDoubleClickListener(event -> openComputedEditor(event.getItem()));
        expressionsGrid.addColumn(expression -> org.ipro.reportstudio.query.VisualQueryExpressionText.render(expression.expression()))
                .setHeader("Выражение").setFlexGrow(1);
        expressionsGrid.addColumn(VisualQueryDefinition.Expression::resultName).setHeader("Псевдоним")
                .setAutoWidth(true).setFlexGrow(0);
        expressionsGrid.addComponentColumn(this::expressionRemoveButton).setAutoWidth(true).setFlexGrow(0);
        expressionsGrid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_NO_BORDER);

        VerticalLayout left = panel("База данных", databaseTree);
        VerticalLayout center = panel("Таблицы", tablesTree);
        VerticalLayout right = new VerticalLayout(new Span("Поля"), fieldsGrid, computedHeader, expressionsGrid);
        right.setPadding(false);
        right.setSpacing(false);
        right.setSizeFull();
        right.getStyle().set("min-height", "0").set("min-width", "0").set("gap", "4px");
        right.setFlexGrow(1, fieldsGrid);
        right.getStyle().set("font-size", "var(--lumo-font-size-xs)");
        expressionsGrid.setMaxHeight("25%");

        HorizontalLayout toCenter = new HorizontalLayout(
                transfer(">", this::addSelectedFromDatabase, "Добавить таблицу/поле в «Таблицы»"),
                transfer(">>", this::addAllFromDatabase, "Добавить все поля таблицы"),
                transfer("<", this::removeSelectedTable, "Убрать таблицу"),
                transfer("<<", this::clearAllTables, "Убрать все таблицы"));
        HorizontalLayout toFields = new HorizontalLayout(
                transfer(">", this::addSelectedField, "Добавить поле"),
                transfer(">>", this::addAllFieldsToRight, "Добавить все поля таблицы"),
                transfer("<", this::removeSelectedField, "Убрать поле"),
                transfer("<<", this::clearAllFields, "Убрать все поля"));
        for (HorizontalLayout column : List.of(toCenter, toFields)) {
            column.setPadding(false);
            column.setSpacing(false);
            column.setWidth("72px");
            column.getStyle().set("flex-direction", "column");
            column.setAlignItems(FlexComponent.Alignment.CENTER);
        }

        HorizontalLayout content = new HorizontalLayout(left, toCenter, center, toFields, right);
        content.setPadding(false);
        content.setSpacing(false);
        content.setWidthFull();
        content.setHeightFull();
        content.setFlexGrow(1, left, center, right);
        content.getStyle().set("min-height", "0");
        add(content);
    }

    /** Доступ для тестов. */
    List<ConstructorTreeNode> databaseRoots() { return databaseRoots; }

    List<ConstructorTreeNode> tableRoots() { return tableRoots; }

    TreeGrid<ConstructorTreeNode> databaseTree() { return databaseTree; }

    TreeGrid<ConstructorTreeNode> tablesTree() { return tablesTree; }

    Grid<VisualQueryDefinition.SelectField> fieldsGrid() { return fieldsGrid; }

    Grid<VisualQueryDefinition.Expression> expressionsGrid() { return expressionsGrid; }

    // === Вычисляемые поля: создание/правка ===

    private VisualQueryTextParser expressionParser;

    private VisualQueryTextParser expressionParser() {
        if (expressionParser == null) expressionParser = new VisualQueryTextParser(draft.catalog());
        return expressionParser;
    }

    /** Диалог без открытия — для тестов. */
    ComputedFieldDialog computedFieldEditorFor(VisualQueryDefinition.Expression editing) {
        return new ComputedFieldDialog(draft, expressionParser(), editing, saved -> {
            expressionsGrid.setItems(draft.expressions());
            changed();
        });
    }

    private void openComputedEditor(VisualQueryDefinition.Expression editing) {
        computedFieldEditorFor(editing).open();
    }

    void refreshFromDraft() {
        databaseRoots = CatalogTreeModel.catalogRoots(draft.roots());
        databaseTree.setItems(databaseRoots, ConstructorTreeNode::children);
        databaseTree.expand(databaseRoots);

        tableRoots = CatalogTreeModel.tableRoots(draft.tables(), draft.roots());
        tablesTree.setItems(tableRoots, ConstructorTreeNode::children);
        tablesTree.expand(tableRoots);

        fieldsGrid.setItems(draft.selections());
        expressionsGrid.setItems(draft.expressions());
    }

    // === Перенос из «Базы данных» в «Таблицы» ===

    void addNode(ConstructorTreeNode node) {
        if (node == null) return;
        switch (node.kind()) {
            case ENTITY -> draft.addTable(node.entity());
            // Ассоциация как поле-ссылка (s.nomenclature) — как в 1С; JOIN по ней
            // не создаётся, а цепочка ассоциаций НАД узлом (если есть) создаётся.
            case ASSOCIATION -> draft.addField(node.ownerEntity().entity(),
                    QueryConstructorDraft.chainOf(node.parent(), node.ownerEntity()),
                    node.association().name());
            case PROPERTY -> draft.addField(node.ownerEntity().entity(), node.chainToOwner(), node.field().name());
        }
        changed();
    }

    private void addSelectedFromDatabase() {
        addNode(databaseTree.asSingleSelect().getValue());
    }

    /** Добавляет все прямые поля сущности/связи (ассоциативная цепочка создаётся автоматически). */
    void addAllFields(ConstructorTreeNode node) {
        if (node == null) return;
        if (node.kind() == ConstructorTreeNode.Kind.ENTITY) {
            for (var field : node.entity().fields()) {
                draft.addField(node.entity(), List.of(), field.name());
            }
        } else if (node.kind() == ConstructorTreeNode.Kind.ASSOCIATION) {
            var base = node.ownerEntity().entity();
            var chain = node.chainToOwner();
            for (var field : node.entity().fields()) {
                draft.addField(base, chain, field.name());
            }
        }
        changed();
    }

    private void addAllFromDatabase() {
        addAllFields(databaseTree.asSingleSelect().getValue());
    }

    // === Перенос из «Таблиц» в «Поля» ===

    private void addSelectedField() {
        var node = tablesTree.asSingleSelect().getValue();
        if (node == null) return;
        if (node.kind() == ConstructorTreeNode.Kind.PROPERTY) {
            addNode(node);
        } else {
            addNode(node);
        }
    }

    private void addAllFieldsToRight() {
        addAllFields(tablesTree.asSingleSelect().getValue());
    }

    // === Удаление ===

    void removeSelectedTable() {
        var node = tablesTree.asSingleSelect().getValue();
        if (node == null || node.tableAlias() == null) return;
        draft.removeTable(node.tableAlias());
        changed();
    }

    void removeSelectedField() {
        var field = fieldsGrid.asSingleSelect().getValue();
        if (field == null) return;
        draft.removeSelection(field);
        changed();
    }

    void clearAllTables() {
        draft.clear();
        changed();
    }

    void clearAllFields() {
        draft.clearSelections();
        changed();
    }

    // === Вспомогательное ===

    private VerticalLayout panel(String title, com.vaadin.flow.component.Component content) {
        Span header = new Span(title);
        header.getStyle().set("font-weight", "600").set("font-size", "var(--lumo-font-size-s)");
        VerticalLayout panel = new VerticalLayout(header, content);
        panel.setPadding(false);
        panel.setSpacing(false);
        panel.setSizeFull();
        panel.getStyle().set("min-height", "0").set("min-width", "0").set("gap", "4px");
        return panel;
    }

    private Button transfer(String caption, Runnable action, String tooltip) {
        Button button = new Button(caption, event -> action.run());
        button.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_SMALL,
                com.vaadin.flow.component.button.ButtonVariant.LUMO_TERTIARY);
        button.getElement().setAttribute("title", tooltip);
        button.setWidthFull();
        return button;
    }

    private Button expressionRemoveButton(VisualQueryDefinition.Expression expression) {
        Button button = new Button("×");
        button.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_SMALL,
                com.vaadin.flow.component.button.ButtonVariant.LUMO_TERTIARY);
        button.getElement().setAttribute("title", "Убрать вычисляемое поле");
        button.addClickListener(event -> {
            draft.removeExpression(expression);
            expressionsGrid.setItems(draft.expressions());
            changed();
        });
        return button;
    }

    private Button removeButton(VisualQueryDefinition.SelectField field) {
        Button button = new Button("×");
        button.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_SMALL,
                com.vaadin.flow.component.button.ButtonVariant.LUMO_TERTIARY);
        button.getElement().setAttribute("title", "Убрать поле");
        button.addClickListener(event -> {
            draft.removeSelection(field);
            changed();
        });
        return button;
    }

    private String kindLabel(ConstructorTreeNode node) {
        return switch (node.kind()) {
            case ENTITY -> "Таблица";
            case PROPERTY -> node.field() == null ? "Поле" : node.field().javaType().getSimpleName();
            case ASSOCIATION -> "Связь";
        };
    }

    /** Редактор alias таблицы: переименование обновляет все ссылки (draft.renameAlias). */
    private com.vaadin.flow.component.Component aliasEditor(ConstructorTreeNode node) {
        if (node.tableAlias() == null) {
            Span empty = new Span("");
            return empty;
        }
        TextField editor = new TextField();
        editor.addThemeVariants(TextFieldVariant.LUMO_SMALL);
        editor.setWidth("92px");
        editor.setValue(node.tableAlias());
        editor.getElement().setAttribute("title", "Переименовать alias таблицы (обновит все ссылки)");
        editor.addValueChangeListener(event -> {
            if (!event.isFromClient()) return;
            String oldAlias = node.tableAlias();
            String newAlias = event.getValue();
            if (newAlias == null || newAlias.isBlank() || newAlias.equals(oldAlias)) return;
            draft.renameAlias(oldAlias, newAlias);
            changed();
        });
        return editor;
    }

    /** Редактор псевдонима поля SELECT. */
    private com.vaadin.flow.component.Component aliasFieldEditor(VisualQueryDefinition.SelectField field) {
        TextField editor = new TextField();
        editor.addThemeVariants(TextFieldVariant.LUMO_SMALL);
        editor.setWidth("92px");
        editor.setValue(field.resultName());
        editor.getElement().setAttribute("title", "Переименовать псевдоним поля");
        editor.addValueChangeListener(event -> {
            if (!event.isFromClient()) return;
            String newName = event.getValue();
            if (newName == null || newName.isBlank() || newName.equals(field.resultName())) return;
            draft.renameResultName(field, newName);
            changed();
        });
        return editor;
    }

    private void changed() {
        onChange.run();
    }
}
