package org.ip.form;

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
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.FieldMetadataInfo;
import org.ipro.metadata.annotation.FieldType;
import org.ip.model.HasDisplayName;
import org.ip.service.LookupService;
import org.ip.views.components.EntityField;
import org.ip.views.components.SearchFunction;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

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
@Component
public class FieldFactory {

    private final LookupService lookupService;
    private final SelectionFormAssembler selectionFormAssembler;

    public FieldFactory(LookupService lookupService, SelectionFormAssembler selectionFormAssembler) {
        this.lookupService = lookupService;
        this.selectionFormAssembler = selectionFormAssembler;
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

    private IntegerField createIntegerField(FieldMetadataInfo info, FormBindingRegistry registry) {
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

    private DateTimePicker createDateTimeField(FieldMetadataInfo info, FormBindingRegistry registry) {
        DateTimePicker field = new DateTimePicker(info.getLabel());
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
                    try {
                        Method m = v.getClass().getMethod("getDisplayName");
                        return m.invoke(v);
                    } catch (Exception e) {
                        return v.toString();
                    }
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

        // Колонки/поля поиска резолвятся из @EntityMetadata.selectColumns() целевой сущности
        // (или её грида, если selectColumns не задан) — один и тот же источник, что и для
        // модального диалога выбора, чтобы автокомплит и диалог не расходились.
        SelectionFormAssembler.ResolvedSelection resolved = selectionFormAssembler.resolveColumns(lookupEntity);
        String[] searchFields = resolved.columns().stream()
            .filter(path -> path.getResolvedType() == FieldType.TEXT)
            .map(ColumnPath::getKey)
            .toArray(String[]::new);

        SearchFunction search = term -> lookupService.search(lookupEntity, searchFields, term, 20);

        EntityField entityField = new EntityField(info.getLabel(), search);
        entityField.setSelectionFormFactory(onSelect ->
            selectionFormAssembler.assemble((Class) lookupEntity, (java.util.function.Consumer) onSelect));

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

    /**
     * Применяет общие настройки (required, readOnly, placeholder) к HasValue-компонентам.
     */
    private void applyCommonSettings(HasValue<?, ?> field, FieldMetadataInfo info) {
        if (field instanceof TextField tf) {
            tf.setRequired(info.isRequired());
            tf.setReadOnly(info.isReadOnly());
            if (!info.getPlaceholder().isEmpty()) tf.setPlaceholder(info.getPlaceholder());
        } else if (field instanceof TextArea ta) {
            ta.setRequired(info.isRequired());
            ta.setReadOnly(info.isReadOnly());
            if (!info.getPlaceholder().isEmpty()) ta.setPlaceholder(info.getPlaceholder());
        } else if (field instanceof EmailField ef) {
            ef.setRequired(info.isRequired());
            ef.setReadOnly(info.isReadOnly());
        } else if (field instanceof PasswordField pf) {
            pf.setRequired(info.isRequired());
            pf.setReadOnly(info.isReadOnly());
        } else if (field instanceof DatePicker dp) {
            dp.setRequired(info.isRequired());
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
                if (field instanceof com.vaadin.flow.component.HasEnabled he) {
                    he.setEnabled(!readOnly);
                }
            }
        ));
    }

}
