package org.ipro.form.builder;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.TextField;
import org.ipro.form.builtin.ListForm;
import org.ipro.form.EntityField;
import org.ipro.form.SearchFunction;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.FieldMetadataInfo;
import org.ipro.metadata.HasDisplayName;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.crud.LookupService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Ряд интерактивных контекст-фильтров: одна и та же панель и в {@code ListForm},
 * и в {@code SelectionForm} целевой сущности (идентичность поведения).
 *
 * Контролы строятся из деклараций {@link ContextFilterField} (вид — из
 * {@link ContextFilterControl}, значения уходят наружу через {@code onValue}):
 * панель состояния не хранит, только рисует. Путь со значением null означает «убрать».
 * Обязательные поля ({@link ContextFilterField#required()}) помечаются « *» в подписи —
 * запрет пустого грида/создания/выбора применяет сама форма, а не панель.
 */
public class ContextFilterPanel extends HorizontalLayout {

    private final Class<?> entityClass;
    private final MetadataResolver metadataResolver;
    private final LookupService lookupService;
    private final ListForm.SelectionFormProvider selectionFormProvider;
    private final BiConsumer<String, Object> onValue;
    private final Map<String, Component> controls = new LinkedHashMap<>();

    public ContextFilterPanel(Class<?> entityClass,
                              List<ContextFilterField> fields,
                              MetadataResolver metadataResolver,
                              LookupService lookupService,
                              ListForm.SelectionFormProvider selectionFormProvider,
                              BiConsumer<String, Object> onValue) {
        this.entityClass = entityClass;
        this.metadataResolver = metadataResolver;
        this.lookupService = lookupService;
        this.selectionFormProvider = selectionFormProvider;
        this.onValue = onValue;
        setWidthFull();
        setSpacing(true);
        setPadding(false);
        setAlignItems(FlexComponent.Alignment.BASELINE);
        setFields(fields);
    }

    /**
     * Добавить недостающие контролы (повторные вызовы состояние не сбрасывают).
     */
    public void setFields(List<ContextFilterField> fields) {
        if (fields == null) return;
        for (ContextFilterField field : fields) {
            controls.computeIfAbsent(field.path(), p -> createControl(field));
        }
    }

    /** Вид контроля выбирается декларативно (ContextFilterField.control()). */
    private Component createControl(ContextFilterField field) {
        return switch (field.control()) {
            case AUTO -> createAutoControl(field);
            case SELECT -> createSelectControl(field);
            case LOOKUP -> createLookupCombo(field, field.lookupSource());
        };
    }

    /**
     * Инференс вида из метаданных поля: lookup-поле → ComboBox из справочника,
     * enum → ComboBox констант, дата → DatePicker, прочее → TextField.
     */
    private Component createAutoControl(ContextFilterField field) {
        try {
            ColumnPath path = ColumnPath.resolve(entityClass, field.path());
            return switch (path.getResolvedType()) {
                case ENUM -> createEnumCombo(field, path.getJavaType());
                case ENTITY_REFERENCE -> path.asFieldMetadata()
                    .filter(FieldMetadataInfo::hasLookup)
                    .map(FieldMetadataInfo::getLookupEntity)
                    .filter(source -> lookupService != null)
                    .<Component>map(source -> createLookupCombo(field, source))
                    .orElseGet(() -> createTextFieldControl(field, path.getResolvedType()));
                case DATE -> createDatePicker(field);
                default -> createTextFieldControl(field, path.getResolvedType());
            };
        } catch (IllegalArgumentException unknownPath) {
            return createTextFieldControl(field, null);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ComboBox<Object> createLookupCombo(ContextFilterField field, Class<?> source) {
        ComboBox<Object> box = new ComboBox<>(displayLabel(field));
        if (lookupService != null && source != null) {
            // D3.5.2: lazy autocomplete вместо findAll всей таблицы.
            org.ipro.form.LookupComboHelper.installSuggestItems(
                box, source, lookupService, metadataResolver);
        }
        box.setItemLabelGenerator(org.ipro.fetch.instance.InstanceNameBridge::displayName);
        box.addValueChangeListener(e -> onValue.accept(field.path(), e.getValue()));
        add(box);
        return box;
    }

    private ComboBox<Object> createEnumCombo(ContextFilterField field, Class<?> javaType) {
        ComboBox<Object> box = new ComboBox<>(displayLabel(field));
        if (javaType.isEnum()) {
            box.setItems((Object[]) javaType.getEnumConstants());
        }
        box.setItemLabelGenerator(String::valueOf);
        box.addValueChangeListener(e -> onValue.accept(field.path(), e.getValue()));
        add(box);
        return box;
    }

    private DatePicker createDatePicker(ContextFilterField field) {
        DatePicker picker = new DatePicker(displayLabel(field));
        picker.addValueChangeListener(e -> onValue.accept(field.path(), e.getValue()));
        add(picker);
        return picker;
    }

    private TextField createTextFieldControl(ContextFilterField field, FieldType type) {
        TextField input = new TextField(displayLabel(field));
        input.addValueChangeListener(e ->
            onValue.accept(field.path(), toTypedValue(e.getValue(), type)));
        add(input);
        return input;
    }

    /**
     * SELECT: редактируемое lookup-поле (EntityField) — ручной ввод с автокомплитом и кнопка
     * «⋯» с формой выбора (SelectionForm), как поля ENTITY_REFERENCE в ItemForm: поиск при вводе
     * идёт по тем же текстовым колонкам, что и диалог. Без провайдера/справочника — честный
     * fallback на ComboBox.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Component createSelectControl(ContextFilterField field) {
        if (selectionFormProvider == null
                || lookupService == null || field.lookupSource() == null) {
            return createLookupCombo(field, field.lookupSource());
        }
        SearchFunction<Object> search = term -> lookupService
            .search(field.lookupSource(), searchFieldsOf(field.lookupSource()), term, 20)
            .stream().map(item -> (Object) item).toList();
        EntityField entityField = new EntityField(displayLabel(field), search);
        entityField.setSelectionFormFactory(onSelect ->
            selectionFormProvider.selectionForm(
                field.lookupSource(), (java.util.function.Consumer) onSelect));
        entityField.addValueChangeListener(value -> onValue.accept(field.path(), value));
        add(entityField);
        return entityField;
    }

    /** Текстовые колонки формы выбора источника — поля поиска автокомплита (как в FieldFactory). */
    private String[] searchFieldsOf(Class<?> sourceClass) {
        if (metadataResolver == null) {
            return new String[0];
        }
        try {
            return metadataResolver.resolve(sourceClass).getSelectColumnPaths().stream()
                .filter(path -> path.getResolvedType() == FieldType.TEXT)
                .map(ColumnPath::getKey)
                .toArray(String[]::new);
        } catch (RuntimeException unresolvable) {
            return new String[0];
        }
    }

    /** Привести введённый текст к типу поля (числа → Long/Double), чтобы не падать при bind. */
    private static Object toTypedValue(String text, FieldType type) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return switch (type == null ? FieldType.TEXT : type) {
                case INTEGER -> Long.valueOf(text.trim());
                case DECIMAL -> Double.valueOf(text.trim());
                default -> text;
            };
        } catch (NumberFormatException notANumber) {
            return text;
        }
    }

    private static String displayLabel(ContextFilterField field) {
        return field.required() ? field.label() + " *" : field.label();
    }
}
