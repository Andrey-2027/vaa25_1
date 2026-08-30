package org.ipro.filter;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Компактный независимый редактор дерева условий. */
public class FilterTreeEditor extends VerticalLayout {
    private final FilterFieldResolver resolver;
    private final Consumer<FilterNode> changeListener;
    private final List<String> parameterNames;
    private final VerticalLayout content = new VerticalLayout();
    private FilterNode root;
    private FilterEntitySelector entitySelector;

    public FilterTreeEditor(FilterFieldResolver resolver, FilterNode initial,
                            Consumer<FilterNode> changeListener) {
        this(resolver, initial, changeListener, List.of());
    }

    public FilterTreeEditor(FilterFieldResolver resolver, FilterNode initial,
                            Consumer<FilterNode> changeListener, List<String> parameterNames) {
        this(resolver, initial, changeListener, parameterNames, null);
    }

    public FilterTreeEditor(FilterFieldResolver resolver, FilterNode initial,
                            Consumer<FilterNode> changeListener, List<String> parameterNames,
                            FilterEntitySelector entitySelector) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.root = initial;
        this.changeListener = changeListener == null ? ignored -> { } : changeListener;
        this.parameterNames = List.copyOf(parameterNames == null ? List.of() : parameterNames);
        this.entitySelector = entitySelector;
        setPadding(false);
        setSpacing(false);
        setWidthFull();
        content.setPadding(false);
        content.setSpacing(false);
        add(content);
        rebuild();
    }

    /**
     * Подключает выбор ссылочных сущностей через форму выбора (SelectionForm).
     * Заменяет комбобокс значений на кнопку «Выбрать…» для ENTITY_REFERENCE-полей.
     */
    public void setEntitySelector(FilterEntitySelector entitySelector) {
        this.entitySelector = entitySelector;
        rebuild();
    }

    public FilterNode getValue() { return root; }

    public void setValue(FilterNode value) {
        root = value;
        rebuild();
    }

    private void rebuild() {
        content.removeAll();
        content.add(root == null ? emptyState() : renderNode(root, true, null));
    }

    /** Пустое состояние: подсказка + кнопки, чтобы добавить первое условие/группу. */
    private Component emptyState() {
        VerticalLayout empty = new VerticalLayout();
        empty.setPadding(false);
        empty.setSpacing(false);
        Span message = new Span("Условия не заданы.");
        message.getStyle().set("color", "var(--lumo-secondary-text-color)");
        Button addCondition = action("+ Условие", e -> {
            FilterConditionNode draft = newEmptyCondition();
            if (draft != null) startWith(draft);
        });
        Button addGroup = action("+ Группа", e -> startWith(new FilterGroup(LogicalOperator.AND, List.of())));
        HorizontalLayout actions = new HorizontalLayout(addCondition, addGroup);
        actions.setPadding(false);
        actions.setSpacing(false);
        empty.add(message, actions);
        return empty;
    }

    /** Первое добавление: корень становится группой с одним узлом (иначе нет кнопок управления). */
    private void startWith(FilterNode node) {
        root = new FilterGroup(LogicalOperator.AND, List.of(node));
        changed();
    }

    private Component renderNode(FilterNode node, boolean rootNode, FilterGroup parent) {
        if (node instanceof FilterConditionNode leaf) return conditionRow(leaf, parent);
        FilterGroup group = (FilterGroup) node;
        VerticalLayout wrapper = new VerticalLayout();
        wrapper.setPadding(false);
        wrapper.setSpacing(false);
        HorizontalLayout header = new HorizontalLayout();
        header.setPadding(false);
        header.setSpacing(false);
        header.setAlignItems(Alignment.CENTER);
        Span title = new Span(rootNode ? "Все условия" : "Группа");
        title.getStyle().set("font-weight", "600");
        ComboBox<LogicalOperator> operator = new ComboBox<>();
        operator.setItems(LogicalOperator.values());
        operator.setValue(group.operator());
        operator.setItemLabelGenerator(value -> value == LogicalOperator.AND ? "И" : "ИЛИ");
        operator.setWidth("72px");
        operator.addValueChangeListener(e -> {
            if (e.getValue() != null && e.isFromClient()) replaceGroup(group, new FilterGroup(e.getValue(), group.children()));
        });
        Button addCondition = action("+ Условие", e -> {
            FilterConditionNode draft = newEmptyCondition();
            if (draft != null) append(group, draft);
        });
        Button addGroup = action("+ Группа", e -> append(group, new FilterGroup(LogicalOperator.AND, List.of())));
        header.add(title, operator, addCondition, addGroup);
        if (!rootNode) header.add(action("×", e -> removeNode(group)));
        wrapper.add(header);
        VerticalLayout children = new VerticalLayout();
        children.setPadding(false);
        children.setSpacing(false);
        children.getStyle().set("margin-left", "10px").set("padding-left", "8px")
                .set("border-left", "2px solid var(--lumo-contrast-10pct)");
        for (FilterNode child : group.children()) children.add(renderNode(child, false, group));
        wrapper.add(children);
        return wrapper;
    }

    private Component conditionRow(FilterConditionNode leaf, FilterGroup parent) {
        FilterCondition condition = leaf.condition();
        ComboBox<FilterFieldResolver.ResolvedFilterField> field = new ComboBox<>();
        field.setItems(resolver.fields());
        field.setItemLabelGenerator(FilterFieldResolver.ResolvedFilterField::label);
        field.setValue(safeResolve(condition.path()));
        field.setWidth("145px");
        field.addValueChangeListener(e -> {
            if (e.getValue() != null && e.isFromClient()) replaceCondition(leaf,
                    new FilterCondition(e.getValue().path(), defaultOperator(e.getValue()), defaultValue(e.getValue()), null, e.getValue().dataType()));
        });
        ComboBox<FilterOperator> operator = new ComboBox<>();
        operator.setItems(operatorsFor(condition.dataType()));
        operator.setValue(condition.operator());
        operator.setItemLabelGenerator(this::operatorLabel);
        operator.setWidth("118px");
        operator.addValueChangeListener(e -> {
            if (e.getValue() != null && e.isFromClient()) replaceCondition(leaf,
                    new FilterCondition(condition.path(), e.getValue(), valueOrNull(condition.value(), e.getValue()),
                            e.getValue() == FilterOperator.BETWEEN ? condition.valueTo() : null, condition.dataType()));
        });
        Component value = valueEditor(leaf, condition);
        HorizontalLayout row = new HorizontalLayout(field, operator, value, action("×", e -> removeNode(leaf)));
        row.setPadding(false);
        row.setSpacing(false);
        row.setAlignItems(Alignment.END);
        row.getStyle().set("margin", "2px 0");
        return row;
    }

    private Component valueEditorSingle(FilterConditionNode leaf, FilterCondition condition, boolean to) {
        if (!parameterNames.isEmpty()) {
            ComboBox<String> parameter = new ComboBox<>();
            parameter.setItems(parameterNames.stream().map(name -> ":" + name).toList());
            parameter.setAllowCustomValue(true);
            parameter.setValue(to ? condition.valueTo() : condition.value());
            parameter.addCustomValueSetListener(e -> replaceCondition(leaf, new FilterCondition(condition.path(), condition.operator(), to ? condition.value() : e.getDetail(), to ? e.getDetail() : condition.valueTo(), condition.dataType())));
            parameter.addValueChangeListener(e -> { if (e.isFromClient()) replaceCondition(leaf, new FilterCondition(condition.path(), condition.operator(), to ? condition.value() : e.getValue(), to ? e.getValue() : condition.valueTo(), condition.dataType())); });
            parameter.setWidth("145px");
            return parameter;
        }
        return textEditor(leaf, condition, to);
    }

    private Component valueEditor(FilterConditionNode leaf, FilterCondition condition) {
        if (condition.operator() == FilterOperator.IS_NULL || condition.operator() == FilterOperator.IS_NOT_NULL)
            return new Span("без значения");
        if (condition.operator() == FilterOperator.BETWEEN) {
            Component from = valueEditorSingle(leaf, condition, false);
            Component to = valueEditorSingle(leaf, condition, true);
            HorizontalLayout range = new HorizontalLayout(from, to);
            range.setPadding(false); range.setSpacing(false);
            return range;
        }
        if (condition.dataType() == FilterDataType.DATE) {
            DatePicker picker = new DatePicker();
            picker.setWidth("145px");
            if (condition.value() != null) picker.setValue(LocalDate.parse(condition.value()));
            picker.addValueChangeListener(e -> replaceCondition(leaf, new FilterCondition(condition.path(), condition.operator(),
                    e.getValue() == null ? null : e.getValue().toString(), null, condition.dataType())));
            return picker;
        }
        if (condition.operator() == FilterOperator.IN) {
            TextField list = textEditor(leaf, condition, false);
            list.setPlaceholder("значения через запятую");
            return list;
        }
        if (condition.dataType() == FilterDataType.ENUM) {
            ComboBox<Object> combo = new ComboBox<>();
            var resolved = resolver.resolve(condition.path());
            List<?> options = resolver.valueOptions(resolved);
            combo.setItems(options);
            if (condition.value() != null) for (Object item : options) {
                String canonical = item instanceof Enum<?> e ? e.name() : String.valueOf(item);
                if (canonical.equals(condition.value())) combo.setValue(item);
            }
            combo.addValueChangeListener(e -> replaceCondition(leaf, new FilterCondition(condition.path(), condition.operator(),
                    canonicalValue(e.getValue()), null, condition.dataType())));
            combo.setWidth("145px");
            return combo;
        }
        if (condition.dataType() == FilterDataType.ENTITY_REFERENCE) {
            var resolved = safeResolve(condition.path());
            if (entitySelector != null && resolved != null) {
                Button choose = new Button(condition.value() == null ? "Выбрать…" : condition.value(), e ->
                        entitySelector.open(resolved.javaType(), selected -> {
                            String canonical = entityOptionLabel(selected);
                            replaceCondition(leaf, new FilterCondition(condition.path(), condition.operator(),
                                    canonical, null, condition.dataType()));
                        }));
                choose.addThemeVariants(ButtonVariant.LUMO_SMALL);
                choose.setWidth("145px");
                choose.setTooltipText("Выбрать " + resolved.label() + " из справочника");
                return choose;
            }
            ComboBox<Object> combo = new ComboBox<>();
            var comboResolved = resolver.resolve(condition.path());
            List<?> options = resolver.valueOptions(comboResolved);
            combo.setItems(options);
            combo.setItemLabelGenerator(this::entityOptionLabel);
            combo.setPlaceholder("выберите значение");
            if (condition.value() != null) for (Object item : options) {
                if (entityOptionLabel(item).equals(condition.value())) combo.setValue(item);
            }
            combo.addValueChangeListener(e -> replaceCondition(leaf, new FilterCondition(condition.path(), condition.operator(),
                    entityOptionLabel(e.getValue()), null, condition.dataType())));
            combo.setWidth("145px");
            return combo;
        }
        if (!parameterNames.isEmpty()) {
            ComboBox<String> parameter = new ComboBox<>();
            parameter.setItems(parameterNames.stream().map(name -> ":" + name).toList());
            parameter.setPlaceholder("значение или параметр");
            parameter.setAllowCustomValue(true);
            parameter.setValue(condition.value());
            parameter.addCustomValueSetListener(e -> replaceCondition(leaf, new FilterCondition(condition.path(), condition.operator(), e.getDetail(), null, condition.dataType())));
            parameter.addValueChangeListener(e -> { if (e.isFromClient()) replaceCondition(leaf, new FilterCondition(condition.path(), condition.operator(), e.getValue(), null, condition.dataType())); });
            parameter.setWidth("145px");
            return parameter;
        }
        return textEditor(leaf, condition, false);
    }

    private TextField textEditor(FilterConditionNode leaf, FilterCondition c, boolean to) {
        TextField text = new TextField();
        text.setWidth("145px");
        text.setValue(Objects.requireNonNullElse(to ? c.valueTo() : c.value(), ""));
        text.addValueChangeListener(e -> replaceCondition(leaf, new FilterCondition(c.path(), c.operator(),
                to ? c.value() : e.getValue(), to ? e.getValue() : c.valueTo(), c.dataType())));
        return text;
    }

    /**
     * Новое условие: по умолчанию — оператор со значением (CONTAINS для текста, иначе EQ),
     * чтобы поле значения сразу было доступно для ввода. null, если полей для фильтра нет.
     */
    private FilterConditionNode newEmptyCondition() {
        FilterFieldResolver.ResolvedFilterField field = resolver.fields().stream().findFirst().orElse(null);
        if (field == null) return null;
        FilterDataType type = field.dataType();
        FilterOperator operator = type == FilterDataType.TEXT ? FilterOperator.CONTAINS : FilterOperator.EQ;
        String value = type == FilterDataType.DATE ? LocalDate.now().toString() : "";
        return new FilterConditionNode(new FilterCondition(field.path(), operator, value, null, type));
    }

    private void append(FilterGroup group, FilterNode node) {
        replaceChildren(group, appendCopy(group.children(), node));
    }

    private void replaceGroup(FilterGroup old, FilterGroup replacement) { root = replace(root, old, replacement); changed(); }
    private void replaceCondition(FilterConditionNode old, FilterCondition replacement) { root = replace(root, old, new FilterConditionNode(replacement)); changed(); }
    private void replaceChildren(FilterGroup old, List<FilterNode> children) { root = replace(root, old, new FilterGroup(old.operator(), children)); changed(); }

    private void removeNode(FilterNode target) {
        if (target == root) { root = null; changed(); return; }
        root = remove(root, target); changed();
    }

    /** Проверяет дерево до передачи наружу; пустые группы не сохраняются и не исполняются. */
    public List<String> validationErrors() {
        List<String> errors = new ArrayList<>();
        validateNode(root, errors, "корень");
        return List.copyOf(errors);
    }

    private void validateNode(FilterNode node, List<String> errors, String location) {
        if (node == null) return;
        if (node instanceof FilterConditionNode conditionNode) {
            validateCondition(conditionNode.condition(), errors, location);
            return;
        }
        FilterGroup group = (FilterGroup) node;
        if (group.children().isEmpty()) errors.add(location + ": пустая группа");
        for (int i = 0; i < group.children().size(); i++) {
            validateNode(group.children().get(i), errors, location + "." + (i + 1));
        }
    }

    private void validateCondition(FilterCondition condition, List<String> errors, String location) {
        try { resolver.resolve(condition.path()); }
        catch (IllegalArgumentException e) { errors.add(location + ": " + e.getMessage()); return; }
        if (FilterCondition.requiresNoValue(condition.operator())) return;
        if (condition.operator() == FilterOperator.BETWEEN) {
            if (isBlank(condition.value()) || isBlank(condition.valueTo())) {
                errors.add(location + ": укажите оба значения (от и до)");
            }
        } else if (isBlank(condition.value())) {
            errors.add(location + ": укажите значение");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void changed() {
        changeListener.accept(root);
        rebuild();
    }

    private FilterNode replace(FilterNode current, FilterNode target, FilterNode replacement) {
        if (current == target) return replacement;
        if (current instanceof FilterGroup group)
            return new FilterGroup(group.operator(), group.children().stream().map(c -> replace(c, target, replacement)).toList());
        return current;
    }

    private FilterNode remove(FilterNode current, FilterNode target) {
        if (current instanceof FilterGroup group)
            return new FilterGroup(group.operator(), group.children().stream().filter(c -> c != target)
                    .map(c -> remove(c, target)).filter(Objects::nonNull).toList());
        return current;
    }

    private List<FilterNode> appendCopy(List<FilterNode> source, FilterNode node) {
        List<FilterNode> result = new ArrayList<>(source); result.add(node); return result;
    }

    private FilterFieldResolver.ResolvedFilterField safeResolve(String path) {
        try { return resolver.resolve(path); }
        catch (IllegalArgumentException e) { return null; }
    }

    private FilterOperator defaultOperator(FilterFieldResolver.ResolvedFilterField f) { return f.dataType() == FilterDataType.TEXT ? FilterOperator.CONTAINS : FilterOperator.EQ; }
    private String defaultValue(FilterFieldResolver.ResolvedFilterField f) { return f.dataType() == FilterDataType.DATE ? LocalDate.now().toString() : ""; }
    private String valueOrNull(String value, FilterOperator op) { return op == FilterOperator.IS_NULL || op == FilterOperator.IS_NOT_NULL ? null : Objects.requireNonNullElse(value, ""); }
    private List<FilterOperator> operatorsFor(FilterDataType type) { return switch (type) {
        case TEXT -> List.of(FilterOperator.EQ, FilterOperator.NE, FilterOperator.CONTAINS, FilterOperator.STARTS_WITH, FilterOperator.IN, FilterOperator.IS_NULL, FilterOperator.IS_NOT_NULL);
        case NUMBER, DATE -> List.of(FilterOperator.EQ, FilterOperator.NE, FilterOperator.GT, FilterOperator.GE, FilterOperator.LT, FilterOperator.LE, FilterOperator.BETWEEN, FilterOperator.IN, FilterOperator.IS_NULL, FilterOperator.IS_NOT_NULL);
        case ENUM, ENTITY_REFERENCE -> List.of(FilterOperator.EQ, FilterOperator.NE, FilterOperator.IN, FilterOperator.IS_NULL, FilterOperator.IS_NOT_NULL);
        case BOOLEAN -> List.of(FilterOperator.EQ, FilterOperator.IS_NULL, FilterOperator.IS_NOT_NULL);
    }; }
    private String canonicalValue(Object value) {
        return value instanceof Enum<?> e ? e.name() : value == null ? null : String.valueOf(value);
    }

    /** Отображаемое/каноническое значение варианта ссылочного поля — как в гриде. */
    private String entityOptionLabel(Object option) {
        if (option == null) return null;
        if (option instanceof org.ipro.metadata.HasDisplayName displayName) {
            String canonical = displayName.getDisplayName();
            if (canonical != null && !canonical.isBlank()) return canonical;
        }
        return String.valueOf(option);
    }

    private String operatorLabel(FilterOperator op) { return switch (op) {
        case EQ -> "Равно"; case NE -> "Не равно"; case CONTAINS -> "Содержит"; case STARTS_WITH -> "Начинается с";
        case GT -> ">"; case GE -> ">="; case LT -> "<"; case LE -> "<="; case BETWEEN -> "Между";
        case IS_NULL -> "Пусто"; case IS_NOT_NULL -> "Заполнено"; case IN -> "В списке";
    }; }
    private Button action(String text, com.vaadin.flow.component.ComponentEventListener<com.vaadin.flow.component.ClickEvent<Button>> listener) {
        Button button = new Button(text, listener); button.addThemeVariants(ButtonVariant.LUMO_SMALL); return button;
    }
}
