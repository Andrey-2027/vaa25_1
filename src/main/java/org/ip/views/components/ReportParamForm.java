package org.ip.views.components;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasValue;
import com.vaadin.flow.component.HasLabel;
import com.vaadin.flow.component.HasSize;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.datetimepicker.DateTimePicker;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.textfield.TextField;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.form.EntityField;
import org.ipro.form.SearchFunction;
import org.ipro.form.SelectionForm;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.crud.EntityLookup;
import org.ipro.reportstudio.dom.ReportParam;
import org.ipro.reportstudio.dom.ReportParamKind;
import org.ipro.reportstudio.dom.ReportParamSource;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.query.register.RegisterSliceReportTemplates;
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
 * <li>ENTITY — существующий RLS-осведомлённый {@link EntityField} (EntityLookup +
 *     SelectionFormAssembler — те же компоненты, что в формах приложения);</li>
 * <li>ENTITY_LIST — повторяющийся EntityField (кнопки «+»/«−»).</li>
 * </ul>
 * Значения читаются через {@link #values()}: скаляры — String/Double/Boolean/LocalDate,
 * сущности — отобранные инстансы (id из них извлечёт ReportParamResolver).
 */
public class ReportParamForm extends VerticalLayout {

    private final Map<String, FieldEntry> fields = new LinkedHashMap<>();
    private final Map<String,Consumer<Object>> writers=new LinkedHashMap<>();
    private final Map<Component,String> rowLabels=new LinkedHashMap<>();
    private boolean inlineLabels;
    private final java.util.Map<String,String> parameterTypes;
    private final java.util.Set<String> dateParameters;
    private final java.util.Set<String> dateTimeParameters;
    private Runnable changeListener = () -> { };

    /** Includes server-side entity selection and changes to dynamically added list rows. */
    public void setChangeListener(Runnable listener) { changeListener = java.util.Objects.requireNonNull(listener); }

    /** Opt-in row layout for UniDR's run window, using the same FormItem pattern as UDR. */
    public void useInlineLabels() {
        if(inlineLabels || rowLabels.isEmpty())return;inlineLabels=true;
        var layout=new FormLayout();layout.setWidthFull();
        layout.setResponsiveSteps(new FormLayout.ResponsiveStep("0",1,FormLayout.ResponsiveStep.LabelsPosition.ASIDE));
        layout.getStyle().set("--vaadin-form-item-label-width","9rem");
        removeAll();
        rowLabels.forEach((component,label)->{clearOwnLabels(component);if(component instanceof HasSize size)size.setWidthFull();layout.addFormItem(component,label);});
        add(layout);
    }
    private static void clearOwnLabels(Component component) {
        if(component instanceof HasLabel labelled){String label=labelled.getLabel();if(label!=null && !label.isBlank())component.getElement().setAttribute("aria-label",label);labelled.setLabel("");}
        if("true".equals(component.getElement().getAttribute("data-parameter-caption")))component.setVisible(false);
        component.getChildren().forEach(ReportParamForm::clearOwnLabels);
    }

    private void observe(Component component) {
        if (component instanceof EntityField<?> entity) entity.addValueChangeListener(value -> changeListener.run());
        else if (component instanceof HasValue<?, ?> value) value.addValueChangeListener(event -> changeListener.run());
    }

    public ReportParamForm(ReportTemplate template, ReportContext context,
                           EntityLookup lookupService, SelectionFormAssembler assembler) {
        this(template.getParams(), context, lookupService, assembler,
            RegisterSliceReportTemplates.dateParameters(template),
            RegisterSliceReportTemplates.dateTimeParameters(template), RegisterSliceReportTemplates.parameterTypes(template));
    }

    public ReportParamForm(List<ReportParam> params, ReportContext context,
                           EntityLookup lookupService, SelectionFormAssembler assembler) {
        this(params, context, lookupService, assembler, java.util.Set.of(), java.util.Set.of(), java.util.Map.of());
    }
    /** Typed callers such as UniDR do not encode input types in default values. */
    public ReportParamForm(List<ReportParam> params,ReportContext context,EntityLookup lookupService,SelectionFormAssembler assembler,
                           Map<String,String> types) {
        this(params,context,lookupService,assembler,types.entrySet().stream().filter(e->e.getValue().equals("java.time.LocalDate")).map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()),
            types.entrySet().stream().filter(e->e.getValue().equals("java.time.LocalDateTime")).map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()),types);
    }

    private ReportParamForm(List<ReportParam> params, ReportContext context,
                            EntityLookup lookupService, SelectionFormAssembler assembler, java.util.Set<String> dateParameters,
                            java.util.Set<String> dateTimeParameters, java.util.Map<String,String> parameterTypes) {
        this.parameterTypes = parameterTypes;
        this.dateParameters = dateParameters;
        this.dateTimeParameters = dateTimeParameters;
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
                String type = parameterTypes.getOrDefault(entry.getKey(), "");
                if (value instanceof Number number) value = switch(type) {
                    case "java.lang.Long", "long" -> new java.math.BigDecimal(number.toString()).longValueExact(); case "java.lang.Integer", "int" -> new java.math.BigDecimal(number.toString()).intValueExact(); case "java.lang.Short", "short" -> new java.math.BigDecimal(number.toString()).shortValueExact();
                    case "java.math.BigDecimal" -> new java.math.BigDecimal(number.toString()); default -> value;
                };
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }
    /** UniDR keeps explicit empty input distinct from an absent value/default. */
    public Map<String,Object> allValues() {
        var result=new LinkedHashMap<>(values());fields.keySet().forEach(name->result.putIfAbsent(name,null));
        return java.util.Collections.unmodifiableMap(result);
    }
    public void setValues(Map<String,Object> values) {
        Runnable previous=changeListener;changeListener=()->{};
        try {values.forEach((name,value)->{var writer=writers.get(name);if(writer!=null)writer.accept(value);});}
        finally {changeListener=previous;}
    }

    // === скалярные поля ===

    private Component createScalarField(ReportParam param) {
        String hint = param.getDefaultValue();
        Component component;
        if (dateTimeParameters.contains(param.getName())) {
            var timestamp = new DateTimePicker(label(param));
            timestamp.setStep(java.time.Duration.ofMillis(1));
            component = timestamp;
        } else if (dateParameters.contains(param.getName())) {
            component = new DatePicker(label(param));
        } else if (java.util.Set.of("java.math.BigDecimal","java.lang.Long","long","java.lang.Integer","int","java.lang.Short","short").contains(parameterTypes.getOrDefault(param.getName(),""))) {
            component=new BigDecimalField(label(param));
        } else if (parameterTypes.getOrDefault(param.getName(), "").matches("(java\\.lang\\.|java\\.math\\.)?(Long|Integer|Short|Byte|BigDecimal|Double|Float)|long|int|short|byte|double|float")) {
            var number = new NumberField(label(param)); number.setStep(parameterTypes.get(param.getName()).matches(".*(Double|Float|BigDecimal)|double|float") ? 0.01 : 1); component = number;
        } else if (java.util.Set.of("boolean","java.lang.Boolean").contains(parameterTypes.getOrDefault(param.getName(), ""))) {
            component = new com.vaadin.flow.component.combobox.ComboBox<Boolean>(label(param), true, false);
        } else if (enumType(parameterTypes.get(param.getName())) != null) {
            var enumeration = new com.vaadin.flow.component.combobox.ComboBox<Object>(label(param));
            enumeration.setItems(enumType(parameterTypes.get(param.getName())).getEnumConstants());
            component = enumeration;
        } else if (hint != null && !hint.isBlank()) {
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

    private static Class<?> enumType(String name) {
        if (name == null || name.isBlank()) return null;
        try { var type = Class.forName(name, false, Thread.currentThread().getContextClassLoader()); return type.isEnum() ? type : null; }
        catch (ClassNotFoundException unknown) { return null; }
    }

    private TextField textField(ReportParam param) {
        TextField textField = new TextField(label(param));
        textField.setClearButtonVisible(true);
        return textField;
    }

    private void addPeriodFields(ReportParam param) {
        DatePicker from = new DatePicker(label(param) + " (с)");
        DatePicker to = new DatePicker(label(param) + " (по)");
        from.setPlaceholder("с");to.setPlaceholder("по");
        from.setRequiredIndicatorVisible(param.isRequired());
        to.setRequiredIndicatorVisible(param.isRequired());
        from.setWidth("min(220px, 100%)");
        to.setWidth("min(220px, 100%)");
        HorizontalLayout row = new HorizontalLayout(from, to);
        row.setWidthFull();row.getStyle().set("flex-wrap","wrap");
        row.setSpacing(true);
        row.setAlignItems(FlexComponent.Alignment.BASELINE);
        add(row);
        rowLabels.put(row,label(param));
        observe(from);observe(to);
        fields.put(param.getName() + "From", new FieldEntry(from::getValue, null));
        fields.put(param.getName() + "To", new FieldEntry(to::getValue, null));
        writers.put(param.getName()+"From",value->from.setValue((java.time.LocalDate)value));
        writers.put(param.getName()+"To",value->to.setValue((java.time.LocalDate)value));
    }

    // === сущностные поля (переиспользование RLS-осведомлённого EntityField) ===

    @SuppressWarnings({"rawtypes", "unchecked"})
    private EntityField createEntityField(ReportParam param, EntityLookup lookupService,
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
        labelSelectionControls(field,label(param));
    }

    private static void labelSelectionControls(Component root,String caption) {
        root.getChildren().forEach(child->{
            if(child instanceof Button button)button.getElement().setAttribute("aria-label","Выбрать из справочника: "+caption);
            else if(child instanceof TextField text){text.setAriaLabel(caption);text.getStyle().set("min-width","0").set("flex","1");}
            else if(child instanceof HorizontalLayout layout){layout.setWidthFull();layout.getStyle().set("min-width","0");}
            labelSelectionControls(child,caption);
        });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void addEntityListField(ReportParam param, EntityLookup lookupService,
                                    SelectionFormAssembler assembler) {
        VerticalLayout list = new VerticalLayout();
        list.setSpacing(true);
        list.setPadding(false);

        List<EntityField> rowFields = new ArrayList<>();
        Consumer<Void> addRow = ignored -> {
            EntityField row = new EntityField(label(param), entitySearch(param, lookupService, assembler));
            row.setWidthFull();
            installSelectionForm(row, param, assembler);
            if(inlineLabels)row.setLabel("");
            observe(row);
            Button remove = new Button(VaadinIcon.MINUS.create(), e -> {
                row.getParent().ifPresent(list::remove);
                rowFields.remove(row);
                changeListener.run();
            });
            remove.addThemeVariants(ButtonVariant.LUMO_ICON, ButtonVariant.LUMO_TERTIARY);
            HorizontalLayout rowLayout = new HorizontalLayout(row, remove);
            rowLayout.setWidthFull();
            rowLayout.setAlignItems(FlexComponent.Alignment.CENTER);
            list.add(rowLayout);
            rowFields.add(row);
        };
        addRow.accept(null);
        writers.put(param.getName(),value->{
            list.removeAll();rowFields.clear();
            var selected=value instanceof java.util.Collection<?> collection?collection:List.of();
            for(Object item:selected){addRow.accept(null);rowFields.getLast().setValue((org.ipro.metadata.HasDisplayName)item);}
            if(rowFields.isEmpty())addRow.accept(null);
        });

        Button addButton = new Button("Добавить", VaadinIcon.PLUS.create());
        addButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        addButton.addClickListener(e -> {addRow.accept(null);changeListener.run();});

        Span caption = new Span(label(param));
        caption.getElement().setAttribute("data-parameter-caption","true");
        caption.getElement().getStyle()
            .set("font-size", "var(--lumo-font-size-s)")
            .set("color", "var(--lumo-secondary-text-color)");
        var group=new VerticalLayout(caption,list,addButton);group.setPadding(false);group.setSpacing(false);group.setWidthFull();
        add(group);rowLabels.put(group,label(param));
        fields.put(param.getName(), new FieldEntry(
            () -> rowFields.stream().map(EntityField::getValue)
                .filter(java.util.Objects::nonNull).toList(),
            readOnly -> rowFields.forEach(f -> f.setReadOnly(readOnly))));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private SearchFunction entitySearch(ReportParam param, EntityLookup lookupService,
                                        SelectionFormAssembler assembler) {
        Class<?> entityClass = entityClassOf(param);
        SelectionFormAssembler.ResolvedSelection resolved = assembler.resolveColumns(entityClass);
        String[] searchFields = resolved.columns().stream()
            .filter(path -> path.getResolvedType() == FieldType.TEXT)
            .map(ColumnPath::getKey)
            .toArray(String[]::new);
        return term -> lookupService.search(entityClass, List.of(searchFields), term, 20);
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
        rowLabels.put(component,component instanceof HasLabel labelled?labelled.getLabel():name);
        observe(component);
        if(component instanceof HasValue<?,?> field)writers.put(name,value->writeField(field,value,enumType(parameterTypes.get(name))));
        if (component instanceof EntityField<?> entityField) {
            writers.put(name,value->writeEntity(entityField,value));
            fields.put(name, new FieldEntry(
                () -> entityField.getValue(),
                readOnly -> entityField.setReadOnly(readOnly)));
        } else if (component instanceof DateTimePicker timestamp) {
            fields.put(name, new FieldEntry(() -> {
                if (timestamp.isInvalid())
                    throw new IllegalArgumentException("Параметр " + name + ": укажите корректную дату и время");
                return timestamp.getValue();
            }, timestamp::setReadOnly));
        } else if (component instanceof HasValue<?, ?> hasValue) {
            fields.put(name, new FieldEntry(hasValue::getValue, hasValue::setReadOnly));
        } else {
            fields.put(name, new FieldEntry(() -> null, readOnlySetter));
        }
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private static void writeField(HasValue field,Object value,Class<?> enumClass) {
        if(enumClass!=null && value instanceof String name)value=Enum.valueOf((Class)enumClass,name);
        if(field instanceof BigDecimalField && value instanceof Number number)value=new java.math.BigDecimal(number.toString());
        if(field instanceof TextField && value==null)value="";
        field.setValue(value);
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    private static void writeEntity(EntityField field,Object value) {
        field.setValue((org.ipro.metadata.HasDisplayName)value);
    }

    /** Считыватель значения + сеттер readOnly (null — поле не переключает readOnly). */
    private record FieldEntry(ValueSupplier read, Consumer<Boolean> readOnlySetter) {
    }

    @FunctionalInterface
    private interface ValueSupplier {
        Object get();
    }
}
