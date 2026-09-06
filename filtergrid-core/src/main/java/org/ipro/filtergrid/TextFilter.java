package org.ipro.filtergrid;

import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import org.ipro.filtergrid.util.ReflectionUtil;

import java.util.function.Predicate;

public class TextFilter<T> implements FieldFilter<T> {
    public enum FilterMode {
        CONTAINS("abc"), EQUALS("="), NOT_EQUALS("≠"),
        STARTS_WITH("[a]bc"), ENDS_WITH("ab[c]"),
        GREATER_OR_EQUAL("≥"), LESS_OR_EQUAL("≤");
        private final String label;
        FilterMode(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    private final ComboBox<FilterMode> modeSelect;
    private final TextField textField;
    private final HorizontalLayout layout;

    public TextFilter() { this(FilterMode.CONTAINS); }
    public TextFilter(FilterMode defaultMode) {
        modeSelect = new ComboBox<>();
        modeSelect.setItems(FilterMode.values());
        modeSelect.setValue(defaultMode);
        modeSelect.setItemLabelGenerator(FilterMode::getLabel);
        modeSelect.getStyle().set("--vaadin-input-field-min-width", "0");
        modeSelect.getStyle().set("--vaadin-combo-box-overlay-width", "160px");
        modeSelect.getStyle().set("font-family", "monospace");
        modeSelect.setWidth("40px");
        modeSelect.addClassName("fg-mode-select");
        modeSelect.addAttachListener(e -> e.getUI().getPage().executeJs(
            "if (!document.getElementById('fg-mode-select-css')) {" +
            "const s=document.createElement('style');s.id='fg-mode-select-css';" +
            "s.textContent='.fg-mode-select::part(toggle-button){display:none!important;}';"+
            "document.head.appendChild(s);}"));
        textField = new TextField();
        textField.setPlaceholder("Фильтр...");
        textField.setValueChangeMode(ValueChangeMode.LAZY);
        textField.setClearButtonVisible(true);
        textField.setWidthFull();
        layout = new HorizontalLayout(modeSelect, textField);
        layout.setPadding(false); layout.setSpacing(false); layout.setWidthFull();
        layout.setFlexGrow(1, textField);
    }
    public TextFilter(String placeholder) { this(); textField.setPlaceholder(placeholder); }
    @Override public HorizontalLayout getComponent() { return layout; }

    @Override public FilterSpecification<T> createSpecification(String fieldPath) {
        String value = textField.getValue();
        if (value == null || value.isBlank()) return null;
        FilterMode mode = modeSelect.getValue() == null ? FilterMode.CONTAINS : modeSelect.getValue();
        String lower = value.toLowerCase();
        return (root, query, cb) -> {
            var path = org.ipro.filtergrid.util.JpaPathUtil.resolve(root, fieldPath);
            if (path.getJavaType() == String.class) {
                @SuppressWarnings("unchecked")
                jakarta.persistence.criteria.Expression<String> str =
                    (jakarta.persistence.criteria.Expression<String>) (jakarta.persistence.criteria.Path<?>) path;
                return switch (mode) {
                    case EQUALS -> cb.equal(cb.lower(str), lower);
                    case NOT_EQUALS -> cb.notEqual(cb.lower(str), lower);
                    case STARTS_WITH -> cb.like(cb.lower(str), lower + "%");
                    case ENDS_WITH -> cb.like(cb.lower(str), "%" + lower);
                    case GREATER_OR_EQUAL -> cb.greaterThanOrEqualTo(cb.lower(str), lower);
                    case LESS_OR_EQUAL -> cb.lessThanOrEqualTo(cb.lower(str), lower);
                    default -> cb.like(cb.lower(str), "%" + lower + "%");
                };
            }
            return switch (mode) {
                case NOT_EQUALS -> cb.notEqual(path, value);
                case GREATER_OR_EQUAL -> cb.greaterThanOrEqualTo(path, value);
                case LESS_OR_EQUAL -> cb.lessThanOrEqualTo(path, value);
                default -> cb.equal(path, value);
            };
        };
    }

    @Override public Predicate<T> createInMemoryPredicate(String fieldPath) {
        String value = textField.getValue();
        if (value == null || value.isBlank()) return null;
        FilterMode mode = modeSelect.getValue() == null ? FilterMode.CONTAINS : modeSelect.getValue();
        String lower = value.toLowerCase();
        return entity -> {
            Object fieldValue = ReflectionUtil.getFieldValue(entity, fieldPath);
            if (fieldValue == null) return false;
            String current = fieldValue.toString().toLowerCase();
            return switch (mode) {
                case EQUALS -> current.equals(lower);
                case NOT_EQUALS -> !current.equals(lower);
                case GREATER_OR_EQUAL -> current.compareTo(lower) >= 0;
                case LESS_OR_EQUAL -> current.compareTo(lower) <= 0;
                case STARTS_WITH -> current.startsWith(lower);
                case ENDS_WITH -> current.endsWith(lower);
                default -> current.contains(lower);
            };
        };
    }
    @Override public boolean hasActiveValue() { return textField.getValue() != null && !textField.getValue().isBlank(); }
    @Override public String getFilterTypeLabel() { return modeSelect.getValue() == null ? "" : modeSelect.getValue().getLabel(); }
    @Override public String getDisplayValue() { return textField.getValue(); }
    public ComboBox<FilterMode> getModeSelect() { return modeSelect; }
    public TextField getTextField() { return textField; }
}
