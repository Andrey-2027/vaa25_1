package org.ipro.form.builder;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasValue;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.datetimepicker.DateTimePicker;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import org.ipro.crud.LookupService;
import org.ipro.filtergrid.PeriodField;
import org.ipro.form.EntityField;
import org.ipro.form.LookupComboHelper;
import org.ipro.form.SearchFunction;
import org.ipro.form.builtin.ListForm;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.MetadataResolver;
import java.util.*;
import java.util.function.BiConsumer;

/** Shared always-visible list/selection panel. Values belong to the opening form. */
public class ContextFilterPanel extends VerticalLayout {
    private final Class<?> entityClass;
    private final MetadataResolver metadataResolver;
    private final LookupService lookupService;
    private final ListForm.SelectionFormProvider selectionFormProvider;
    private final BiConsumer<String, Object> onValue;
    private final Map<String, ContextFilterField> fields = new LinkedHashMap<>();
    private final Map<String, Component> controls = new LinkedHashMap<>();
    private Map<String, Object> fixed = Map.of();
    private boolean syncing;
    private boolean compact;

    public ContextFilterPanel(Class<?> entityClass, List<ContextFilterField> fields,
            MetadataResolver metadataResolver, LookupService lookupService,
            ListForm.SelectionFormProvider selectionFormProvider, BiConsumer<String, Object> onValue) {
        this.entityClass = entityClass; this.metadataResolver = metadataResolver;
        this.lookupService = lookupService; this.selectionFormProvider = selectionFormProvider; this.onValue = onValue;
        setPadding(false); setSpacing(false); setWidthFull();
        getStyle().set("flex-shrink", "0"); setFields(fields);
    }
    public void setFields(List<ContextFilterField> declarations) {
        if (declarations == null) return;
        boolean changed = false;
        for (var field : declarations) if (!fields.containsKey(field.path())) {
            fields.put(field.path(), field); changed = true;
        }
        if (changed) render();
    }
    public Component getControl(String path) { return controls.get(path); }
    public List<ContextFilterField> getFields() { return List.copyOf(fields.values()); }
    private void render() {
        Map<String, Object> previous = new LinkedHashMap<>();
        controls.forEach((path, control) -> {
            if (control instanceof EntityField<?> entity) previous.put(path, entity.getValue());
            else if (control instanceof HasValue<?, ?> value) previous.put(path, value.getValue());
        });
        removeAll(); controls.clear();
        Map<String, FormLayout> groups = new LinkedHashMap<>();
        for (var field : fields.values()) {
            String group = field.options().group();
            String key = group == null ? "field:" + field.path() : "group:" + group;
            FormLayout row = groups.computeIfAbsent(key, ignored -> {
                FormLayout layout = new FormLayout(); layout.setWidthFull();
                layout.getStyle().set("--vaadin-form-item-label-width", "10em");
                layout.setMaxWidth(group == null ? "42em" : "72em");
                int columns = group == null ? 1 : (int) fields.values().stream()
                    .filter(item -> group.equals(item.options().group())).count();
                layout.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1, FormLayout.ResponsiveStep.LabelsPosition.TOP),
                    new FormLayout.ResponsiveStep(group == null ? "420px" : "700px", columns,
                        field.options().labelsAbove() ? FormLayout.ResponsiveStep.LabelsPosition.TOP : FormLayout.ResponsiveStep.LabelsPosition.ASIDE));
                add(layout); return layout;
            });
            Component control;
            if (fixed.containsKey(field.path())) {
                TextField locked = new TextField(); locked.setReadOnly(true);
                locked.setValue(display(fixed.get(field.path()))); locked.setHelperText("Задан при открытии"); control = locked;
            } else control = createControl(field);
            control.getElement().getStyle().set("width", "100%").set("min-width", "0");
            controls.put(field.path(), control);
            row.addFormItem(control, field.labelFor(entityClass) + (field.required() ? " *" : ""));
        }
        syncControls(previous); setCompact(compact);
    }
    private static String display(Object value) {
        if (value instanceof Boolean flag) return flag ? "Да" : "Нет";
        if (value instanceof Collection<?> values) return String.join(", ", values.stream().map(ContextFilterPanel::display).toList());
        return org.ipro.fetch.instance.InstanceNameBridge.displayName(value);
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Component createControl(ContextFilterField field) {
        var path = ColumnPath.resolve(entityClass, field.path());
        Component control;
        if (field.options().operation() == ContextFilterOptions.Operation.PERIOD) {
            control = switch (path.getResolvedType()) {
                case DATE -> PeriodField.dates(); case DATETIME -> PeriodField.dateTimes();
                default -> throw new IllegalArgumentException("Period filter requires date: " + field.path());
            };
        } else if (field.control() == ContextFilterControl.SELECT
                || field.control() == ContextFilterControl.AUTO && path.getResolvedType() == org.ipro.metadata.annotation.FieldType.ENTITY_REFERENCE) {
            Class source = field.lookupSource() != null ? field.lookupSource() : path.asFieldMetadata()
                .filter(org.ipro.metadata.FieldMetadataInfo::hasLookup).map(org.ipro.metadata.FieldMetadataInfo::getLookupEntity)
                .orElse(path.getJavaType());
            if (selectionFormProvider != null && lookupService != null) {
                org.ipro.form.FilterableSearchFunction<Object> search = (term, filters) -> lookupService.search(source,
                    searchFieldsOf(source), term, 20, List.of(), filters);
                EntityField entity = new EntityField(field.labelFor(entityClass), search);
                entity.setLabel(""); entity.setInputWidth("100%");
                entity.setSelectionFilter(field.options().selectionFilters(), (onSelect, filters) -> {
                    var selection = selectionFormProvider.selectionForm(source, onSelect);
                    selection.setFixedFilters(filters); return selection;
                });
                entity.addValueChangeListener(value -> push(field.path(), value)); return entity;
            }
            control = lookup(field, source);
        } else if (field.control() == ContextFilterControl.LOOKUP) control = lookup(field, field.lookupSource());
        else control = switch (path.getResolvedType()) {
            case ENUM -> { ComboBox box = new ComboBox(); box.setItems(path.getJavaType().getEnumConstants()); yield box; }
            case BOOLEAN -> { ComboBox<Boolean> box = new ComboBox<>(); box.setItems(true, false);
                box.setItemLabelGenerator(value -> value ? "Да" : "Нет"); yield box; }
            case DATE -> new DatePicker(); case DATETIME -> new DateTimePicker();
            default -> new TextField();
        };
        if (control instanceof ComboBox<?> box) box.setClearButtonVisible(true);
        if (control instanceof TextField text) {
            text.setClearButtonVisible(true);
            text.addValueChangeListener(event -> {
                Object value = event.getValue().isBlank() ? null : event.getValue();
                text.setInvalid(false);
                if (value != null) {
                    try {
                        if (path.getResolvedType() == org.ipro.metadata.annotation.FieldType.INTEGER
                                || path.getResolvedType() == org.ipro.metadata.annotation.FieldType.DECIMAL)
                            value = org.ipro.filtergrid.filter.FilterValueCodec.decode(value.toString().trim(),
                                new org.ipro.filtergrid.filter.FilterFieldResolver.ResolvedFilterField(path.getKey(),
                                    field.labelFor(entityClass), path.getJavaType(), org.ipro.filtergrid.filter.FilterDataType.NUMBER, true));
                    } catch (IllegalArgumentException invalid) { text.setInvalid(true); text.setErrorMessage("Введите число"); return; }
                }
                push(field.path(), value);
            });
        } else ((HasValue) control).addValueChangeListener(event -> push(field.path(), event.getValue()));
        return control;
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private ComboBox<?> lookup(ContextFilterField field, Class<?> source) {
        ComboBox box = new ComboBox();
        if (lookupService != null && source != null) {
            box.setItems(query -> lookupService.search(source,
                searchFieldsOf(source),
                String.valueOf(query.getFilter().orElse("")), 20, List.of(), field.options().selectionFilters()).stream());
        }
        box.setItemLabelGenerator(org.ipro.fetch.instance.InstanceNameBridge::displayName); return box;
    }
    private void push(String path, Object value) {
        if (!syncing && !fixed.containsKey(path)) onValue.accept(path, ContextFilterConditions.empty(value) ? null : value);
    }
    private String[] searchFieldsOf(Class<?> source) {
        return LookupComboHelper.textSearchFields(source, metadataResolver).toArray(String[]::new);
    }
    public void setValues(Map<String, Object> values, Map<String, Object> opening) {
        Map<String, Object> nextFixed = opening == null ? Map.of() : Map.copyOf(opening);
        boolean changed = !fixed.equals(nextFixed); fixed = nextFixed;
        for (String path : fixed.keySet()) fields.putIfAbsent(path, ContextFilterField.auto(path, null));
        if (changed) render(); syncControls(values);
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void syncControls(Map<String, Object> values) {
        syncing = true;
        try {
            controls.forEach((path, control) -> {
                if (fixed.containsKey(path)) { ((TextField) control).setValue(display(fixed.get(path))); return; }
                Object value = values == null ? null : values.get(path);
                if (control instanceof EntityField entity) entity.setValue((org.ipro.metadata.HasDisplayName) value);
                else if (control instanceof HasValue input) {
                    if (value == null) input.clear(); else input.setValue(control instanceof TextField ? value.toString() : value);
                }
            });
        } finally { syncing = false; }
    }
    public void setCompact(boolean value) {
        compact = value;
        controls.values().forEach(control -> {
            control.getElement().getThemeList().set("small", value);
            if (control instanceof PeriodField<?> period) period.setCompact(value);
        });
    }
}
