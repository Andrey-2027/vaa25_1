package org.ip.views.components;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasValue;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.TextField;
import org.ip.form.SelectionFormAssembler;
import org.ip.form.builtin.SelectionForm;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.annotation.FieldType;
import org.ip.service.LookupService;
import org.ipro.reportstudio.dom.ReportParam;
import org.ipro.reportstudio.dom.ReportParamKind;
import org.ipro.reportstudio.dom.ReportParamSource;
import org.ipro.reportstudio.param.ReportContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Форма запуска отчёта, генерируемая из схемы параметров шаблона (Фаза 3).
 * <ul>
 * <li>видимость — showOnForm; порядок — position; required — обязательная метка;</li>
 * <li>источники не из формы: CONTEXT — скрывается (дефолт из плана, значение приходит
 *     из контекста запуска), DEFAULT/COMPUTED — значения вычисляет резолвер, поле не нужно;</li>
 * <li>SCALAR — TextField/NumberField/Checkbox/DatePicker (эвристика по defaultValue);</li>
 * <li>PERIOD — два DatePicker ({@code nameFrom}/{@code nameTo});</li>
 * <li>ENTITY — существующий RLS-осведомлённый {@link EntityField} (LookupService +
 *     SelectionFormAssembler — те же компоненты, что в формах приложения);</li>
 * <li>ENTITY_LIST — повторяющийся EntityField (кнопки «+»/«−»).</li>
 * </ul>
 * Значения читаются через {@link #values()}: скаляры — String/Double/Boolean/LocalDate,
 * сущности — отобранные инстансы (id из них извлечёт ReportParamResolver).
 */
public class ReportParamForm extends VerticalLayout {

    private final Map<String, FieldEntry> fields = new LinkedHashMap<>();

    public ReportParamForm(List<ReportParam> params, ReportContext context,
                           LookupService lookupService, SelectionFormAssembler assembler) {
        setSpacing(false);
        setPadding(false);
        if (params == null) {
            return;
        }
        List<ReportParam> ordered = params.stream()
            .sorted(Comparator.comparingInt(ReportParam::getPosition))
            .toList();
        for (ReportParam param : ordered) {
            if (!param.isShowOnForm() || param.getValueSource() != ReportParamSource.FORM) {
                continue;
            }
            switch (param.getKind()) {
                case SCALAR -> addField(param.getName(), createScalarField(param), null);
                case PERIOD -> addPeriodFields(param);
                case ENTITY -> addField(param.getName(), createEntityField(param, lookupService,
                    assembler), null);
                case ENTITY_LIST -> addEntityListField(param, lookupService, assembler);
            }
        }
    }

    /** Значения формы: ключи — имена параметров (для PERIOD — nameFrom/nameTo). */
    public Map<String, Object> values() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, FieldEntry> entry : fields.entrySet()) {
            Object value = entry.getValue().read().get();
            if (value != null && !(value instanceof String s && s.isBlank())) {
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }

    // === скалярные поля ===

    private Component createScalarField(ReportParam param) {
        String hint = param.getDefaultValue();
        Component component;
        if (hint != null && !hint.isBlank()) {
            String trimmed = hint.trim();
            if (trimmed.matches("-?\\d+(\\.\\d+)?")) {
                NumberField numberField = new NumberField(label(param));
                numberField.setStep(trimmed.contains(".") ? 0.01d : 1d);
                component = numberField;
            } else if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
                component = new Checkbox(label(param));
            } else if (trimmed.matches("\\d{4}-\\d{2}-\\d{2}")) {
                component = new DatePicker(label(param));
            } else {
                component = textField(param);
            }
        } else {
            component = textField(param);
        }
        if (component instanceof HasValue<?, ?> field) {
            field.setRequiredIndicatorVisible(param.isRequired());
        }
        return component;
    }

    private TextField textField(ReportParam param) {
        TextField textField = new TextField(label(param));
        textField.setClearButtonVisible(true);
        return textField;
    }

    private void addPeriodFields(ReportParam param) {
        DatePicker from = new DatePicker(label(param) + " (с)");
        DatePicker to = new DatePicker(label(param) + " (по)");
        from.setRequiredIndicatorVisible(param.isRequired());
        to.setRequiredIndicatorVisible(param.isRequired());
        from.setWidth("220px");
        to.setWidth("220px");
        HorizontalLayout row = new HorizontalLayout(from, to);
        row.setSpacing(true);
        row.setAlignItems(FlexComponent.Alignment.BASELINE);
        add(row);
        fields.put(param.getName() + "From", new FieldEntry(from::getValue, null));
        fields.put(param.getName() + "To", new FieldEntry(to::getValue, null));
    }

    // === сущностные поля (переиспользование RLS-осведомлённого EntityField) ===

    @SuppressWarnings({"rawtypes", "unchecked"})
    private EntityField createEntityField(ReportParam param, LookupService lookupService,
                                          SelectionFormAssembler assembler) {
        EntityField field = new EntityField(label(param), entitySearch(param, lookupService, assembler));
        field.setWidthFull();
        installSelectionForm(field, param, assembler);
        return field;
    }

    /**
     * Кнопка «…» поля — Форма Выбора ({@code assembler.assemble}: JpaFilterGrid над
     * {@code BaseService.findAll(spec, pageable)} той же сущности, колонки из
     * {@code @EntityMetadata.selectColumns()}). Дефолтная форма выбора re используется
     * приложения, как в {@code FieldFactory}.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void installSelectionForm(EntityField field, ReportParam param,
                                      SelectionFormAssembler assembler) {
        Class<?> entityClass = entityClassOf(param);
        field.setSelectionFormFactory(onSelect ->
            (SelectionForm) assembler.assemble((Class) entityClass, (Consumer) onSelect));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void addEntityListField(ReportParam param, LookupService lookupService,
                                    SelectionFormAssembler assembler) {
        VerticalLayout list = new VerticalLayout();
        list.setSpacing(true);
        list.setPadding(false);

        List<EntityField> rowFields = new ArrayList<>();
        Consumer<Void> addRow = ignored -> {
            EntityField row = new EntityField(label(param), entitySearch(param, lookupService, assembler));
            row.setWidthFull();
            installSelectionForm(row, param, assembler);
            Button remove = new Button(VaadinIcon.MINUS.create(), e -> {
                list.remove(row);
                rowFields.remove(row);
            });
            remove.addThemeVariants(ButtonVariant.LUMO_ICON, ButtonVariant.LUMO_TERTIARY);
            HorizontalLayout rowLayout = new HorizontalLayout(row, remove);
            rowLayout.setWidthFull();
            rowLayout.setAlignItems(FlexComponent.Alignment.CENTER);
            list.add(rowLayout);
            rowFields.add(row);
        };
        addRow.accept(null);

        Button addButton = new Button("Добавить", VaadinIcon.PLUS.create());
        addButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        addButton.addClickListener(e -> addRow.accept(null));

        Span caption = new Span(label(param));
        caption.getElement().getStyle()
            .set("font-size", "var(--lumo-font-size-s)")
            .set("color", "var(--lumo-secondary-text-color)");
        add(caption, list, addButton);
        fields.put(param.getName(), new FieldEntry(
            () -> rowFields.stream().map(EntityField::getValue)
                .filter(java.util.Objects::nonNull).toList(),
            readOnly -> rowFields.forEach(f -> f.setReadOnly(readOnly))));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private SearchFunction entitySearch(ReportParam param, LookupService lookupService,
                                        SelectionFormAssembler assembler) {
        Class<?> entityClass = entityClassOf(param);
        SelectionFormAssembler.ResolvedSelection resolved = assembler.resolveColumns(entityClass);
        String[] searchFields = resolved.columns().stream()
            .filter(path -> path.getResolvedType() == FieldType.TEXT)
            .map(ColumnPath::getKey)
            .toArray(String[]::new);
        return term -> lookupService.search(entityClass, searchFields, term, 20);
    }

    private static Class<?> entityClassOf(ReportParam param) {
        try {
            return Class.forName(param.getEntityClass());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Параметр :" + param.getName()
                + " — класс " + param.getEntityClass() + " не найден", e);
        }
    }

    private static String label(ReportParam param) {
        return param.getCaption() != null && !param.getCaption().isBlank()
            ? param.getCaption() : param.getName();
    }

    private void addField(String name, Component component, Consumer<Boolean> readOnlySetter) {
        add(component);
        if (component instanceof EntityField<?> entityField) {
            fields.put(name, new FieldEntry(
                () -> entityField.getValue(),
                readOnly -> entityField.setReadOnly(readOnly)));
        } else if (component instanceof HasValue<?, ?> hasValue) {
            fields.put(name, new FieldEntry(hasValue::getValue, hasValue::setReadOnly));
        } else {
            fields.put(name, new FieldEntry(() -> null, readOnlySetter));
        }
    }

    /** Считыватель значения + сеттер readOnly (null — поле не переключает readOnly). */
    private record FieldEntry(ValueSupplier read, Consumer<Boolean> readOnlySetter) {
    }

    @FunctionalInterface
    private interface ValueSupplier {
        Object get();
    }
}