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
    private final VerticalLayout content = new VerticalLayout();
    private FilterNode root;

    public FilterTreeEditor(FilterFieldResolver resolver, FilterNode initial,
                            Consumer<FilterNode> changeListener) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.root = initial;
        this.changeListener = changeListener == null ? ignored -> { } : changeListener;
        setPadding(false);
        setSpacing(false);
        setWidthFull();
        content.setPadding(false);
        content.setSpacing(false);
        add(content);
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

    private Span emptyState() {
        Span empty = new Span("Условия не заданы. Добавьте условие или группу.");
        empty.getStyle().set("color", "var(--lumo-secondary-text-color)");
        return empty;
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
        Button addCondition = action("+ Условие", e -> append(group,
                new FilterConditionNode(new FilterCondition(defaultPath(), FilterOperator.EQ, "", null, defaultType()))));
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

    private Component valueEditor(FilterConditionNode leaf, FilterCondition condition) {
        if (condition.operator() == FilterOperator.IS_NULL || condition.operator() == FilterOperator.IS_NOT_NULL)
            return new Span("без значения");
        if (condition.operator() == FilterOperator.BETWEEN) {
            TextField from = textEditor(leaf, condition, false);
            TextField to = textEditor(leaf, condition, true);
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

    private void changed() { changeListener.accept(root); rebuild(); }

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
        catch (IllegalArgumentException e) { return resolver.fields().stream().findFirst().orElse(null); }
    }

    private String defaultPath() { return resolver.fields().stream().findFirst().map(FilterFieldResolver.ResolvedFilterField::path).orElse(""); }
    private FilterDataType defaultType() { return resolver.fields().stream().findFirst().map(FilterFieldResolver.ResolvedFilterField::dataType).orElse(FilterDataType.TEXT); }
    private FilterOperator defaultOperator(FilterFieldResolver.ResolvedFilterField f) { return f.dataType() == FilterDataType.TEXT ? FilterOperator.CONTAINS : FilterOperator.EQ; }
    private String defaultValue(FilterFieldResolver.ResolvedFilterField f) { return f.dataType() == FilterDataType.DATE ? LocalDate.now().toString() : ""; }
    private String valueOrNull(String value, FilterOperator op) { return op == FilterOperator.IS_NULL || op == FilterOperator.IS_NOT_NULL ? null : Objects.requireNonNullElse(value, ""); }
    private List<FilterOperator> operatorsFor(FilterDataType type) { return switch (type) {
        case TEXT -> List.of(FilterOperator.EQ, FilterOperator.NE, FilterOperator.CONTAINS, FilterOperator.STARTS_WITH, FilterOperator.IS_NULL, FilterOperator.IS_NOT_NULL);
        case NUMBER, DATE -> List.of(FilterOperator.EQ, FilterOperator.NE, FilterOperator.GT, FilterOperator.GE, FilterOperator.LT, FilterOperator.LE, FilterOperator.BETWEEN, FilterOperator.IS_NULL, FilterOperator.IS_NOT_NULL);
        case ENUM, ENTITY_REFERENCE -> List.of(FilterOperator.EQ, FilterOperator.NE, FilterOperator.IS_NULL, FilterOperator.IS_NOT_NULL);
        case BOOLEAN -> List.of(FilterOperator.EQ, FilterOperator.IS_NULL, FilterOperator.IS_NOT_NULL);
    }; }
    private String canonicalValue(Object value) {
        return value instanceof Enum<?> e ? e.name() : value == null ? null : String.valueOf(value);
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
