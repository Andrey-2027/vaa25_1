package org.ipro.form;

import com.vaadin.flow.component.HasValue;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.datetimepicker.DateTimePicker;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.textfield.EmailField;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import org.ipro.metadata.FieldMetadataInfo;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.metadata.HasDisplayName;
import org.ipro.crud.LookupService;
import org.ipro.form.builder.SelectionColumnResolver;
import org.ipro.form.registry.FormRegistry;
import org.ipro.form.EntityField;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.form.SearchFunction;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Главный создатель Vaadin-компонентов на основе FieldMetadataInfo.
 *
 * Вызывается ItemForm для генерации полей формы.
 * Каждый созданный компонент регистрирует FormBinding в FormBindingRegistry,
 * чтобы потом можно было синхронизировать значения между UI и entity.
 *
 * Поддерживаемые FieldType:
 *   TEXT, TEXT_AREA, EMAIL, PASSWORD   → TextField / TextArea / EmailField / PasswordField
 *   INTEGER, DECIMAL                   → IntegerField / BigDecimalField
 *   BOOLEAN                            → Checkbox
 *   DATE, DATETIME                     → DatePicker / DateTimePicker
 *   ENUM                               → ComboBox<Enum>
 *   ENTITY_REFERENCE                   → EntityField (1С-стиль "Поле ввода")
 */
/**
 * Регистрация бина — в {@code FormAutoConfiguration}, каноническим 4-арг конструктором: стереотип
 * снят, чтобы владелец регистрации был ровно один (D3.5.5).
 */
public class FieldFactory {

    private final LookupService lookupService;
    private final SelectionFormAssembler selectionFormAssembler;
    private final org.ipro.metadata.MetadataResolver metadataResolver;
    private final FormRegistry formRegistry;

    @Autowired
    public FieldFactory(LookupService lookupService, SelectionFormAssembler selectionFormAssembler,
                        org.ipro.metadata.MetadataResolver metadataResolver, FormRegistry formRegistry) {
        this.lookupService = lookupService;
        this.selectionFormAssembler = selectionFormAssembler;
        this.metadataResolver = metadataResolver;
        this.formRegistry = formRegistry;
    }

    /** Совместимость для тестов и ручной сборки без metadata/form registry. */
    public FieldFactory(LookupService lookupService, SelectionFormAssembler selectionFormAssembler) {
        this(lookupService, selectionFormAssembler, new org.ipro.metadata.MetadataResolver(),
            new FormRegistry());
    }

    /**
     * Главный метод. Создаёт Vaadin-компонент по FieldType из FieldMetadataInfo
     * и регистрирует биндинг в registry.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public com.vaadin.flow.component.Component createField(FieldMetadataInfo fieldInfo, FormBindingRegistry registry) {
        FieldType type = fieldInfo.getResolvedType();
        return switch (type) {
            case TEXT -> createTextField(fieldInfo, registry);
            case TEXT_AREA -> createTextArea(fieldInfo, registry);
            case EMAIL -> createEmailField(fieldInfo, registry);
            case PASSWORD -> createPasswordField(fieldInfo, registry);
            case INTEGER -> createIntegerField(fieldInfo, registry);
            case DECIMAL -> createDecimalField(fieldInfo, registry);
            case BOOLEAN -> createBooleanField(fieldInfo, registry);
            case DATE -> createDateField(fieldInfo, registry);
            case DATETIME -> createDateTimeField(fieldInfo, registry);
            case ENUM -> createEnumField(fieldInfo, registry);
            case ENTITY_REFERENCE -> createEntityField(fieldInfo, registry);
            case AUTO -> throw new IllegalStateException(
                "FieldType.AUTO should be resolved in FieldMetadataInfo. " +
                "Field: " + fieldInfo.getName() + " of type " + fieldInfo.getJavaType());
        };
    }

    // === Текстовые поля ===

    private TextField createTextField(FieldMetadataInfo info, FormBindingRegistry registry) {
        TextField field = new TextField(info.getLabel());
        applyCommonSettings(field, info);
        registerSimpleBinding(info, field, registry);
        return field;
    }

    private TextArea createTextArea(FieldMetadataInfo info, FormBindingRegistry registry) {
        TextArea field = new TextArea(info.getLabel());
        applyCommonSettings(field, info);
        registerSimpleBinding(info, field, registry);
        return field;
    }

    private EmailField createEmailField(FieldMetadataInfo info, FormBindingRegistry registry) {
        EmailField field = new EmailField(info.getLabel());
        applyCommonSettings(field, info);
        registerSimpleBinding(info, field, registry);
        return field;
    }

    private PasswordField createPasswordField(FieldMetadataInfo info, FormBindingRegistry registry) {
        PasswordField field = new PasswordField(info.getLabel());
        applyCommonSettings(field, info);
        registerSimpleBinding(info, field, registry);
        return field;
    }

    // === Числовые поля ===

    private com.vaadin.flow.component.Component createIntegerField(FieldMetadataInfo info, FormBindingRegistry registry) {
        if (info.getJavaType() == Long.class || info.getJavaType() == long.class) {
            // IntegerField stores 32-bit values. BigDecimalField preserves every Long digit
            // and exact conversion rejects fractions/overflow instead of truncating ids.
            BigDecimalField field = new BigDecimalField(info.getLabel());
            applyCommonSettings(field, info);
            registry.add(new FormBinding(info, field,
                entity -> entity == null ? null : info.getValue(entity),
                (entity, value) -> info.setValue(entity, value == null ? null
                    : ((java.math.BigDecimal) value).longValueExact()),
                field::getValue,
                value -> field.setValue(value == null ? null : java.math.BigDecimal.valueOf((Long) value)),
                value -> value == null,
                readOnly -> { field.setReadOnly(readOnly); field.setEnabled(!readOnly); }));
            return field;
        }
        IntegerField field = new IntegerField(info.getLabel());
        applyCommonSettings(field, info);
        registerSimpleBinding(info, field, registry);
        return field;
    }

    private BigDecimalField createDecimalField(FieldMetadataInfo info, FormBindingRegistry registry) {
        BigDecimalField field = new BigDecimalField(info.getLabel());
        applyCommonSettings(field, info);
        registerSimpleBinding(info, field, registry);
        return field;
    }

    // === Boolean ===

    private Checkbox createBooleanField(FieldMetadataInfo info, FormBindingRegistry registry) {
        Checkbox field = new Checkbox(info.getLabel());
        field.setReadOnly(info.isReadOnly());
        registerSimpleBinding(info, field, registry);
        return field;
    }

    // === Дата / Время ===

    private DatePicker createDateField(FieldMetadataInfo info, FormBindingRegistry registry) {
        DatePicker field = new DatePicker(info.getLabel());
        applyCommonSettings(field, info);
        registerSimpleBinding(info, field, registry);
        return field;
    }

    private com.vaadin.flow.component.Component createDateTimeField(
            FieldMetadataInfo info, FormBindingRegistry registry) {
        DateTimePicker field = new DateTimePicker(info.getLabel());
        field.setStep(java.time.Duration.ofMillis(1));
        applyCommonSettings(field, info);
        registerSimpleBinding(info, field, registry);
        return field;
    }

    // === Enum ===

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ComboBox createEnumField(FieldMetadataInfo info, FormBindingRegistry registry) {
        Class<?> javaType = info.getJavaType();
        if (!javaType.isEnum()) {
            throw new IllegalStateException(
                "Field " + info.getName() + " has FieldType.ENUM but is not enum: " + javaType);
        }
        ComboBox field = new ComboBox(info.getLabel());
        field.setItems(javaType.getEnumConstants());
        field.setReadOnly(info.isReadOnly());
        registerSimpleBinding(info, field, registry);
        return field;
    }

    // === ENTITY_REFERENCE — главный кейс ===

    @SuppressWarnings({"rawtypes", "unchecked"})
    private com.vaadin.flow.component.Component createEntityField(FieldMetadataInfo info, FormBindingRegistry registry) {
        return createEntityField(info, registry, info.getLookupVariant());
    }

    /** Form-local selection variant; entity metadata remains unchanged. */
    public com.vaadin.flow.component.Component createField(FieldMetadataInfo info, FormBindingRegistry registry, String variant) {
        if (variant == null) return createField(info, registry);
        if (info.getResolvedType() != org.ipro.metadata.annotation.FieldType.ENTITY_REFERENCE)
            throw new IllegalArgumentException("Selection variant requires a reference field: " + info.getName());
        return createEntityField(info, registry, variant);
    }

    private com.vaadin.flow.component.Component createEntityField(FieldMetadataInfo info, FormBindingRegistry registry, String variant) {
        if (!info.hasLookup()) {
            // Fallback: read-only TextField с displayName связанной сущности
            TextField fallback = new TextField(info.getLabel());
            fallback.setReadOnly(true);
            fallback.setPlaceholder("(не настроено @Lookup)");
            // Биндинг: read из entity → displayName, write игнорируется
            registry.add(new FormBinding(
                info, fallback,
                entity -> {
                    Object v = entity == null ? null : info.getValue(entity);
                    if (v == null) return null;
                    // Та же лестница, что у всех каналов отображения (ADX-09), а не
                    // собственная рефлексия по getDisplayName().
                    return org.ipro.fetch.instance.InstanceNameBridge.displayName(v);
                },
                (entity, value) -> { /* read-only */ },
                fallback::getValue,
                value -> fallback.setValue(value == null ? "" : value.toString()),
                value -> value == null || value.toString().isEmpty(),
                readOnly -> { /* already read-only */ }
            ));
            return fallback;
        }

        Class<?> lookupEntity = info.getLookupEntity();

        // Колонки принадлежат диалогу. Поиск автокомплита независимо читает представление.
        // Без варианта — прежний путь через ассемблер
        // (default: selectColumns целевой сущности либо её грид).
        // С вариантом — через общий резолвер приоритета
        // (вариант → selectColumns → listColumns); неизвестный вариант
        // здесь — ошибка конфигурации поля, а не тихий fallback.
        final SelectionFormAssembler.ResolvedSelection resolved;
        final String effectiveVariant;
        if (variant == null || variant.isBlank()) {
            resolved = selectionFormAssembler.resolveColumns(lookupEntity);
            effectiveVariant = null;
        } else {
            SelectionFormAssembler.ResolvedSelection r = SelectionColumnResolver.resolve(
                metadataResolver, formRegistry, lookupEntity, variant);
            if (r == null) {
                throw new IllegalStateException(
                    "Unknown SELECTION variant '" + variant + "' for " + lookupEntity.getName() +
                    " referenced from field '" + info.getName() + "' — fallback to default is forbidden. " +
                    "Check that the variant is registered in the corresponding selection customization.");
            }
            resolved = r;
            effectiveVariant = (variant == null || variant.isBlank()) ? null : variant;
        }

        String[] searchFields = LookupComboHelper.textSearchFields(lookupEntity, metadataResolver)
            .toArray(String[]::new);

        // LookupService применяет сценарий LOOKUP внутри своей read-границы.
        // Б1: поле умеет применять отбор выбора (setSelectionFilter) и в автокомплите.
        // Без отбора вызов прежний: поле без ограничений ищет ровно как раньше.
        LookupSearchFunction search = (term, filters, paths) -> filters == null || filters.isEmpty()
            ? paths.isEmpty() ? lookupService.search(lookupEntity, searchFields, term, 20)
                : lookupService.search(lookupEntity, searchFields, term, 20, paths)
            : lookupService.search(lookupEntity, searchFields, term, 20, paths.isEmpty() ? null : paths, filters);

        EntityField entityField = new EntityField(info.getLabel(), search);
        entityField.setSelectionFormFactory((LookupSelectionFormFactory) (onSelect, filters, paths) -> {
            org.ipro.form.SelectionForm form = selectionFormAssembler.assemble(
                (Class) lookupEntity, (java.util.function.Consumer) onSelect,
                filters, resolved, effectiveVariant, paths);
            // Ряд контекст-фильтров варианта поля (либо общий): та же лестница,
            // что у программного открытия.
            org.ipro.form.builder.SelectionContextFilters.attach(form, lookupEntity,
                effectiveVariant, filters, formRegistry, metadataResolver,
                lookupService, selectionFormAssembler);
            return form;
        });
        entityField.setRequiredIndicatorVisible(info.isRequired());
        entityField.setReadOnly(info.isReadOnly());

        // Биндинг через публичный API EntityField
        registry.add(new FormBinding(
            info,
            entityField,
            entity -> entity == null ? null : info.getValue(entity),  // readFromEntity
            (entity, value) -> info.setValue(entity, value),         // writeToEntity
            entityField::getValue,                                   // readFromComponent
            value -> {
                if (value == null) {
                    entityField.clear();
                } else {
                    entityField.setValue((HasDisplayName) value);
                }
            },
            value -> value == null,                                  // isEmpty
            entityField::setReadOnly                                 // setReadOnly
        ));

        return entityField;
    }

    // === Утилиты ===

    /** Bind an application-supplied HasValue editor using the same metadata and clean baseline. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public com.vaadin.flow.component.Component bindEditor(FieldMetadataInfo info, com.vaadin.flow.component.Component editor,
            FormBindingRegistry registry) {
        if (!(editor instanceof HasValue field))
            throw new IllegalArgumentException("Custom editor must implement HasValue: " + info.getName());
        if (editor.getParent().isPresent())
            throw new IllegalArgumentException("Custom editor is already attached; create a fresh editor: " + info.getName());
        Class<?> expected = org.springframework.util.ClassUtils.resolvePrimitiveIfNecessary(info.getJavaType());
        Class<?> editorType = org.springframework.core.ResolvableType.forClass(editor.getClass())
            .as(HasValue.class).getGeneric(1).resolve();
        if (editorType != null && !editorType.isAssignableFrom(expected))
            throw new IllegalArgumentException("Custom editor value type " + editorType.getName()
                + " does not accept " + expected.getName() + " for " + info.getName());
        java.util.function.Consumer<Object> check = value -> {
            if (value != null && !expected.isInstance(value))
                throw new IllegalArgumentException("Custom editor value must be " + expected.getName() + " for " + info.getName());
        };
        check.accept(field.getValue());
        if (editor instanceof com.vaadin.flow.component.HasLabel labelled) labelled.setLabel(info.getLabel());
        boolean numbered = info.getField() != null
            && info.getField().getAnnotation(org.ipro.numbering.annotation.Numbered.class) != null;
        field.setRequiredIndicatorVisible(!numbered && info.isRequired());
        field.setReadOnly(info.isReadOnly());
        applyCommonSettings(field, info);
        registry.add(FormBinding.forMetadata(info, editor,
            entity -> entity == null ? null : info.getValue(entity),
            (entity, value) -> { check.accept(value); info.setValue(entity, value); },
            () -> { Object value = field.getValue(); check.accept(value); return value; },
            value -> { check.accept(value); if (value == null) field.clear(); else field.setValue(value); },
            value -> value == null || value instanceof String text && text.isBlank(),
            readOnly -> field.setReadOnly(readOnly || info.isReadOnly())));
        return editor;
    }

    /**
     * Применяет общие настройки (required, readOnly, placeholder) к HasValue-компонентам.
     * Авто-нумеруемое поле ({@code @Numbered}): required в компоненте не выставляем (пустое
     * значение допустимо — номер присвоит сервис при сохранении), подсказываем placeholder'ом.
     */
    private void applyCommonSettings(HasValue<?, ?> field, FieldMetadataInfo info) {
        boolean numbered = info.getField() != null
            && info.getField().getAnnotation(org.ipro.numbering.annotation.Numbered.class) != null;
        boolean required = numbered ? false : info.isRequired();
        if (field instanceof TextField tf) {
            tf.setRequired(required);
            tf.setReadOnly(info.isReadOnly());
            if (numbered) {
                tf.setPlaceholder("присвоится автоматически");
            }
        } else if (field instanceof TextArea ta) {
            ta.setRequired(required);
            ta.setReadOnly(info.isReadOnly());
        } else if (field instanceof EmailField ef) {
            ef.setRequired(required);
            ef.setReadOnly(info.isReadOnly());
        } else if (field instanceof PasswordField pf) {
            pf.setRequired(required);
            pf.setReadOnly(info.isReadOnly());
        } else if (field instanceof DatePicker dp) {
            dp.setRequired(required);
            dp.setReadOnly(info.isReadOnly());
        } else if (field instanceof DateTimePicker dtp) {
            // DateTimePicker не имеет setRequired в Vaadin 25
            dtp.setReadOnly(info.isReadOnly());
        } else if (field instanceof IntegerField inf) {
            inf.setReadOnly(info.isReadOnly());
        } else if (field instanceof BigDecimalField bdf) {
            bdf.setReadOnly(info.isReadOnly());
        }
    }

    /**
     * Регистрирует биндинг для HasValue-компонента (TextField, DatePicker, ComboBox и т.д.).
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void registerSimpleBinding(FieldMetadataInfo info, HasValue field, FormBindingRegistry registry) {
        registry.add(new FormBinding(
            info,
            (com.vaadin.flow.component.Component) field,
            entity -> entity == null ? null : info.getValue(entity),  // readFromEntity
            (entity, value) -> info.setValue(entity, value),         // writeToEntity
            field::getValue,                                          // readFromComponent
            value -> {
                if (value == null) {
                    field.clear();
                } else {
                    ((HasValue) field).setValue(value);
                }
            },
            value -> {
                if (value == null) return true;
                if (value instanceof String s) return s.isBlank();
                return false;
            },
            readOnly -> {
                field.setReadOnly(readOnly);
                if (field instanceof com.vaadin.flow.component.HasEnabled he) {
                    he.setEnabled(!readOnly);
                }
            }
        ));
    }

}
