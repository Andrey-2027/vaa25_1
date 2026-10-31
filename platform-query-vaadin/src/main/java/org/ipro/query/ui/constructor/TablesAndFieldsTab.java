package org.ipro.query.ui.constructor;

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
import org.ipro.query.VisualQueryDefinition;
import org.ipro.query.VisualQueryTextParser;

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

    private final org.ipro.query.ui.catalog.QuerySourceTree<ConstructorTreeNode> databaseTree = new org.ipro.query.ui.catalog.QuerySourceTree<>();
    private final TextField catalogSearch = new TextField();
    private final TreeGrid<ConstructorTreeNode> tablesTree = new TreeGrid<>();
    private final Grid<VisualQueryDefinition.SelectField> fieldsGrid = new Grid<>();
    /** Вычисляемые поля (CASE/арифметика/функции) — восстановленные из текста, без редактора. */
    private final Grid<VisualQueryDefinition.Expression> expressionsGrid = new Grid<>();
    /** Корни деревьев последнего refreshFromDraft (для тестов — TreeGrid не даёт list data view). */
    private List<ConstructorTreeNode> databaseRoots = List.of();
    private List<ConstructorTreeNode> tableRoots = List.of();
    private List<ConstructorTreeNode> cteRoots = List.of();
    private org.ipro.query.register.RegisterSliceReportTemplates sliceRecipes;
    private java.util.function.BiConsumer<String, String> addSlice;
    private java.util.function.Consumer<String> editSlice;
    private final Button sliceSettings = new Button("Настроить срез");

    TablesAndFieldsTab(QueryConstructorDraft draft, Runnable onChange) {
        this.draft = draft;
        this.onChange = onChange == null ? () -> { } : onChange;

        setPadding(false);
        setSpacing(false);
        setWidthFull();
        setHeightFull();
        getStyle().set("min-height", "0");

        databaseTree.configure(CatalogTreeModel::label, CatalogTreeModel::name, CatalogTreeModel::category, CatalogTreeModel::dataType);
        catalogSearch.setPlaceholder("Сущность или поле"); catalogSearch.setClearButtonVisible(true);
        catalogSearch.setValueChangeMode(com.vaadin.flow.data.value.ValueChangeMode.EAGER);
        catalogSearch.addValueChangeListener(event -> refreshFromDraft());
        databaseTree.addItemDoubleClickListener(event -> addNode(event.getItem()));

        // Русское имя таблицы в иерархии («Спецификации»), техническое — отдельной колонкой.
        tablesTree.addHierarchyColumn(this::displayName).setHeader("Таблицы")
                .setFlexGrow(1).setResizable(true);
        tablesTree.addColumn(ConstructorTreeNode::caption).setHeader("Сущность").setFlexGrow(1).setResizable(true);
        tablesTree.addComponentColumn(this::aliasEditor).setHeader("Alias").setAutoWidth(true).setFlexGrow(0);
        tablesTree.setTooltipGenerator(node -> node.caption());
        tablesTree.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_NO_BORDER);
        tablesTree.addItemDoubleClickListener(event -> {
            if (event.getItem().entity() != null && draft.lists().stream().anyMatch(s -> s.name().equals(event.getItem().entity().entityName()))) {
                openList(event.getItem().entity().entityName()); return;
            }
            if (event.getItem().kind() == ConstructorTreeNode.Kind.PROPERTY) {
                addNode(event.getItem());
            }
        });

        // Русское имя поля («Код», «Наименование»); технический путь — в подсказке.
        fieldsGrid.addColumn(field -> draft.displayFieldName(field.path())).setHeader("Поле").setFlexGrow(1);
        fieldsGrid.setTooltipGenerator(field -> draft.displayPath(field.path()));
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
        expressionsGrid.addColumn(expression -> org.ipro.query.VisualQueryExpressionText.render(expression.expression()))
                .setHeader("Выражение").setFlexGrow(1);
        expressionsGrid.addColumn(VisualQueryDefinition.Expression::resultName).setHeader("Псевдоним")
                .setAutoWidth(true).setFlexGrow(0);
        expressionsGrid.addComponentColumn(this::expressionRemoveButton).setAutoWidth(true).setFlexGrow(0);
        expressionsGrid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_NO_BORDER);

        VerticalLayout left = panel("База данных", databaseTree);
        left.addComponentAtIndex(1, catalogSearch);
        var presentation = new Button("Добавить представление", event -> {
            var node = tablesTree.asSingleSelect().getValue();
            if (node == null) node = databaseTree.asSingleSelect().getValue();
            addPresentation(node);
        });
        left.addComponentAtIndex(2, presentation);
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
            column.addClassName("query-constructor-transfers");
            column.setPadding(false);
            column.setSpacing(false);
            column.setWidth("36px");
            column.getStyle().set("flex-direction", "column");
            column.setAlignItems(FlexComponent.Alignment.CENTER);
        }

        HorizontalLayout content = new HorizontalLayout(left, toCenter, center, toFields, right);
        content.setPadding(false);
        content.setSpacing(false);
        content.setWidthFull();
        content.setHeightFull();
        content.setFlexGrow(1, left, center, right);
        content.setFlexShrink(0, toCenter, toFields);
        content.getStyle().set("min-height", "0");
        sliceSettings.setVisible(false);
        sliceSettings.addClickListener(event -> {
            var selected = tablesTree.asSingleSelect().getValue();
            if (selected != null && selected.entity() != null && editSlice != null)
                editSlice.accept(selected.entity().entityName());
        });
        tablesTree.asSingleSelect().addValueChangeListener(event -> {
            var selected = event.getValue();
            sliceSettings.setEnabled(selected != null && selected.entity() != null
                && draft.slices().stream().anyMatch(slice -> slice.name().equals(selected.entity().entityName())));
        });
        var addList = new Button("Объединить значения в строку…", event -> openList(null));
        var editList = new Button("Настроить список…", event -> {
            var selected = tablesTree.asSingleSelect().getValue();
            if (selected != null && selected.entity() != null) openList(selected.entity().entityName());
        });
        editList.setEnabled(false);
        tablesTree.asSingleSelect().addValueChangeListener(event -> editList.setEnabled(event.getValue() != null
                && event.getValue().entity() != null && draft.lists().stream().anyMatch(s -> s.name().equals(event.getValue().entity().entityName()))));
        add(new HorizontalLayout(sliceSettings, addList, editList), content);
        setFlexGrow(1, content);
    }

    ListSettingsDialog listEditorFor(String name) {
        var current = draft.lists().stream().filter(s -> s.name().equals(name)).findFirst().orElse(null);
        return new ListSettingsDialog(draft, current, this::changed);
    }

    private void openList(String name) {
        if (!draft.isMainStage() || !draft.hasRoot()) {
            com.vaadin.flow.component.notification.Notification.show("Выберите источник итогового запроса перед добавлением списка"); return;
        }
        if (name != null && draft.lists().stream().noneMatch(s -> s.name().equals(name))) return;
        listEditorFor(name).open();
    }

    void setSliceSupport(org.ipro.query.register.RegisterSliceReportTemplates recipes,
                         java.util.function.BiConsumer<String, String> add,
                         java.util.function.Consumer<String> edit) {
        sliceRecipes = recipes; addSlice = add; editSlice = edit;
        sliceSettings.setVisible(recipes != null); sliceSettings.setEnabled(false);
    }

    /** Доступ для тестов. */
    List<ConstructorTreeNode> databaseRoots() { return databaseRoots; }

    List<ConstructorTreeNode> tableRoots() { return tableRoots; }

    List<ConstructorTreeNode> cteRoots() { return cteRoots; }

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
        databaseRoots = CatalogTreeModel.sourceRoots(draft.roots(), sliceRecipes, catalogSearch.getValue());
        cteRoots = draft.availableCteEntities().stream()
                .map(entity -> new ConstructorTreeNode(ConstructorTreeNode.Kind.CTE,
                        entity.caption(), entity, null, null,
                        entity.fields().stream().filter(field -> !field.technical() || draft.lists().stream().noneMatch(s -> s.name().equals(entity.entityName()))).map(field -> new ConstructorTreeNode(
                                ConstructorTreeNode.Kind.PROPERTY, field.caption(), entity, field, null, List.of())).toList()))
                .map(node -> CatalogTreeModel.filter(node, catalogSearch.getValue())).filter(java.util.Objects::nonNull).toList();
        databaseRoots = new java.util.ArrayList<>(databaseRoots);
        if (!cteRoots.isEmpty()) databaseRoots.add(new ConstructorTreeNode(ConstructorTreeNode.Kind.GROUP,
            "Источники запроса", null, null, null, cteRoots));
        databaseTree.setItems(databaseRoots, ConstructorTreeNode::children);
        // Сущности не раскрываются автоматически: каталог компактный, поля
        // раскрывает пользователь двойным кликом по сущности.

        tableRoots = CatalogTreeModel.tableRoots(draft.tables(), draft.roots());
        tablesTree.setItems(tableRoots, ConstructorTreeNode::children);
        tablesTree.expand(tableRoots);

        fieldsGrid.setItems(draft.selections());
        expressionsGrid.setItems(draft.expressions());
    }

    // === Перенос из «Базы данных» в «Таблицы» ===

    void addNode(ConstructorTreeNode node) {
        if (node == null) return;
        var sliceNode = sliceOwner(node);
        if (sliceNode != null && addSlice != null) {
            addSlice.accept(sliceNode.entity().entityName(), node.kind() == ConstructorTreeNode.Kind.PROPERTY ? node.field().name() : null);
            return;
        }
        if (node.field() != null && node.field().name().equals("recorder")) { addPresentation(node); return; }
        switch (node.kind()) {
            case ENTITY -> draft.addTable(node.entity());
            // Виртуальный CTE: без корня — становится корнем этапа; при уже
            // выбранном корне — добавляется независимым JOIN, построение
            // запроса не затирается.
            case CTE -> draft.addCteTable(node.entity().entityName());
            case SLICE, GROUP -> { }
            // Ассоциация как поле-ссылка (s.nomenclature) — как в 1С; JOIN по ней
            // не создаётся, а цепочка ассоциаций НАД узлом (если есть) создаётся.
            case ASSOCIATION -> draft.addField(node.ownerEntity().entity(),
                    QueryConstructorDraft.chainOf(node.parent(), node.ownerEntity()),
                    node.association().name());
            case PROPERTY -> {
                var cteOwner = cteOwnerOf(node);
                if (cteOwner != null) {
                    // Поле виртуальной таблицы: CTE добавляется при отсутствии
                    // (корнем или независимым JOIN), поле — под её актуальным alias.
                    String alias = draft.addCteTable(cteOwner.entity().entityName());
                    if (alias != null) draft.addSelection(alias + "." + node.field().name(), node.field().name());
                } else {
                    draft.addField(node.ownerEntity().entity(), node.chainToOwner(), node.field().name());
                }
            }
        }
        changed();
    }

    void addPresentation(ConstructorTreeNode node) {
        if (node == null || node.kind() == ConstructorTreeNode.Kind.GROUP) return;
        var slice = sliceOwner(node);
        if (slice != null) {
            if (addSlice != null && node.field() != null && (node.field().referenceType() != null || node.field().name().equals("recorder"))) addSlice.accept(slice.entity().entityName(), "@presentation:" + node.field().name());
            return;
        }
        var owner = node.ownerEntity();
        var cte = cteOwnerOf(node);
        if (owner == null && cte == null) return;
        String alias = cte != null ? draft.addCteTable(cte.entity().entityName()) : draft.addTable(owner.entity());
        var path = new StringBuilder(alias);
        if (node.kind() != ConstructorTreeNode.Kind.ENTITY && node.kind() != ConstructorTreeNode.Kind.CTE) {
            var parts = new java.util.ArrayList<String>();
            for (var current = node; current != owner && current != cte && current != null; current = current.parent()) {
                if (current.association() != null) parts.addFirst(current.association().name());
                else if (current.field() != null) parts.addFirst(current.field().name());
            }
            for (var part : parts) path.append('.').append(part);
        }
        draft.addExpression("presentation", new org.ipro.query.VisualQueryExpression.FunctionCall("Представление", List.of(new org.ipro.query.VisualQueryExpression.FieldRef(path.toString()))));
        changed();
    }

    /** Узел-виртуальная таблица (CTE), владеющий полем; для обычных сущностей — null. */
    private static ConstructorTreeNode cteOwnerOf(ConstructorTreeNode node) {
        ConstructorTreeNode current = node;
        while (current != null && current.kind() != ConstructorTreeNode.Kind.CTE) current = current.parent();
        return current;
    }

    private static ConstructorTreeNode sliceOwner(ConstructorTreeNode node) {
        for (var current = node; current != null; current = current.parent())
            if (current.kind() == ConstructorTreeNode.Kind.SLICE) return current;
        return null;
    }

    private void addSelectedFromDatabase() {
        addNode(databaseTree.asSingleSelect().getValue());
    }

    /** Добавляет все прямые поля сущности/связи (ассоциативная цепочка создаётся автоматически). */
    void addAllFields(ConstructorTreeNode node) {
        if (node == null) return;
        var sliceNode = sliceOwner(node);
        if (sliceNode != null && addSlice != null) { addSlice.accept(sliceNode.entity().entityName(), "*"); return; }
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
        } else if (node.kind() == ConstructorTreeNode.Kind.CTE) {
            String alias = draft.addCteTable(node.entity().entityName());
            if (alias != null) {
                for (var field : node.entity().fields()) {
                    draft.addSelection(alias + "." + field.name(), field.name());
                }
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
            case CTE -> draft.slices().stream().anyMatch(source -> source.name().equals(node.entity().entityName())) ? "Срез последних" : "Временная таблица";
            case SLICE -> "Срез последних";
            case GROUP -> "";
        };
    }

    /** Русское имя узла из описания метаданных; техническое имя — как fallback. */
    private String displayName(ConstructorTreeNode node) {
        return switch (node.kind()) {
            case SLICE -> "Срез последних";
            case GROUP -> "";
            case ENTITY, CTE -> {
                String caption = node.entity() == null ? null : node.entity().caption();
                yield caption == null || caption.isBlank() ? node.caption() : caption;
            }
            case PROPERTY -> node.field() == null || node.field().caption() == null
                    || node.field().caption().isBlank() ? node.caption() : node.field().caption();
            case ASSOCIATION -> node.association() == null || node.association().caption() == null
                    || node.association().caption().isBlank() ? node.caption() : node.association().caption();
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
