package org.ip.views.forms;

import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import org.ip.model.AttributeType;
import org.ip.model.AttributeValue;
import org.ip.model.AttributeValueType;
import org.ip.model.NomAttributeValue;
import org.ip.service.AttributeValueService;
import org.ipro.crud.IdentifiableEntity;
import org.ipro.crud.LookupService;
import org.ipro.form.BindingDescriptor;
import org.ipro.form.EntityField;
import org.ipro.form.FieldFactory;
import org.ipro.form.SelectionForm;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.form.builtin.ItemForm;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.FieldMetadataInfo;
import org.ipro.metadata.HasDisplayName;
import org.ipro.metadata.annotation.FieldType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Форма строки табличной части «Атрибуты номенклатуры»: выбирается существующий атрибут,
 * значение вводится по его типу.
 *
 * <p>Тип поля значения определяется выбранным {@link AttributeType#getValueType()}:
 * <ul>
 *   <li>{@code STRING} — свободный текст;</li>
 *   <li>{@code NUMBER} — свободный текст, каноническую форму даёт сервер;</li>
 *   <li>{@code ENUM} — выбор из значений внутреннего справочника этого типа;</li>
 *   <li>{@code REF} — выбор строки целевого словаря типа.</li>
 * </ul>
 *
 * <p>Форма только собирает ввод и кладёт его в черновик строки
 * ({@code enteredValue}/{@code enteredRefId}); строку словаря {@link AttributeValue}
 * создаёт или находит сервер внутри транзакции сохранения номенклатуры
 * ({@link org.ip.application.catalog.NomAttributeValueValueRule}). Поэтому закрытие
 * карточки без сохранения не оставляет «мусорных» значений в едином словаре.
 *
 * <p>Поля значений подключены через {@link #bindExternal} — как обычные поля формы они
 * участвуют в загрузке, dirty-контроле, required-валидации и read-only. Каждый ключ
 * подключается ровно один раз: компонент переиспользуется при смене типа атрибута.
 */
public class NomAttributeValueItemForm extends ItemForm<NomAttributeValue> {

    private final AttributeValueService attributeValueService;
    private final LookupService lookupService;
    private final SelectionFormAssembler selectionFormAssembler;

    private final EntityField<AttributeType> attrTypeField;
    private final VerticalLayout valueArea = new VerticalLayout();

    private TextField textValueField;
    private ComboBox<AttributeValue> enumValueField;
    private EntityField<HasDisplayName> refValueField;
    private Class<?> refTargetClass;

    public NomAttributeValueItemForm(List<FieldMetadataInfo> formFields,
                                     FieldFactory fieldFactory,
                                     AttributeValueService attributeValueService,
                                     LookupService lookupService,
                                     SelectionFormAssembler selectionFormAssembler) {
        super(NomAttributeValue.class, formFields, fieldFactory);
        this.attributeValueService = attributeValueService;
        this.lookupService = lookupService;
        this.selectionFormAssembler = selectionFormAssembler;

        this.attrTypeField = entityField("attrType");
        // В диалоге выбора предлагаются только активные атрибуты; серверная проверка
        // активности всё равно остаётся обязательной (NomAttributeValueValueRule).
        attrTypeField.setSelectionFilter(java.util.Map.of("active", true),
            (onSelect, filters) -> selectionFormAssembler.assemble(
                AttributeType.class, onSelect, filters));
        attrTypeField.addValueChangeListener(this::onTypeChanged);

        valueArea.setPadding(false);
        valueArea.setSpacing(false);
        valueArea.setWidthFull();
        addComponentAtIndex(getComponentCount() - 1, valueArea);

        rebuildValueArea(null, null);
    }

    @Override
    public void setEntity(NomAttributeValue entity) {
        super.setEntity(entity);
        rebuildValueArea(attrTypeField.getValue(), entity);
    }

    private void onTypeChanged(AttributeType type) {
        rebuildValueArea(type, peekEntity());
    }

    // === Поле значения по типу атрибута ===

    private void rebuildValueArea(AttributeType type, NomAttributeValue row) {
        valueArea.removeAll();
        if (type == null) {
            valueArea.add(hint("Выберите атрибут — после этого появится поле значения."));
            return;
        }
        if (!type.isActive()) {
            valueArea.add(hint("Атрибут «" + type.getDisplayName()
                + "» неактивен — значение указать нельзя."));
            return;
        }
        AttributeValueType valueType = type.getValueType() == null
            ? AttributeValueType.STRING : type.getValueType();
        switch (valueType) {
            case STRING, NUMBER -> {
                TextField field = textField();
                field.setLabel(valueType == AttributeValueType.NUMBER
                    ? "Значение (число)" : "Значение");
                field.setPlaceholder(valueType == AttributeValueType.NUMBER
                    ? "например, 1,5" : "");
                field.setValue(initialText(row, type));
                valueArea.add(field);
            }
            case ENUM -> {
                ComboBox<AttributeValue> field = enumField();
                field.setItems(attributeValueService.findByAttrType(type));
                field.setValue(valueOfType(row, type));
                valueArea.add(field);
            }
            case REF -> {
                refTargetClass = attributeValueService.resolveTargetDictionary(type);
                EntityField<HasDisplayName> field = refField();
                field.setLabel("Значение ("
                    + shortClassName(type.getTargetDictionary()) + ")");
                HasDisplayName current = valueOfType(row, type) == null
                    ? null : snapshot(valueOfType(row, type));
                if (current == null) {
                    field.clear();
                } else {
                    field.setValue(current);
                }
                valueArea.add(field);
            }
        }
    }

    private TextField textField() {
        if (textValueField == null) {
            textValueField = new TextField();
            textValueField.setWidthFull();
            bindExternal(new BindingDescriptor("enteredValue", "Значение", false), textValueField,
                NomAttributeValue::getEnteredValue,
                (row, value) -> row.setEnteredValue(value == null || value.isBlank() ? null : value),
                textValueField::getValue,
                value -> textValueField.setValue(value == null ? "" : value),
                value -> value == null || value.isBlank(),
                textValueField::setReadOnly);
        }
        return textValueField;
    }

    private ComboBox<AttributeValue> enumField() {
        if (enumValueField == null) {
            enumValueField = new ComboBox<>();
            enumValueField.setLabel("Значение");
            enumValueField.setWidthFull();
            enumValueField.setItemLabelGenerator(AttributeValue::getDisplayName);
            bindExternal(new BindingDescriptor("attrValue", "Значение", false), enumValueField,
                NomAttributeValue::getAttrValue,
                NomAttributeValue::setAttrValue,
                enumValueField::getValue,
                enumValueField::setValue,
                value -> value == null,
                enumValueField::setReadOnly);
        }
        return enumValueField;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private EntityField<HasDisplayName> refField() {
        if (refValueField == null) {
            refValueField = new EntityField<>("Значение", term -> {
                Class<?> target = refTargetClass;
                if (target == null) {
                    return List.of();
                }
                return (List<HasDisplayName>) (List) lookupService.search(
                    target, refSearchFields(target), term, 20);
            });
            refValueField.setSelectionFormFactory(onSelect -> {
                Class<?> target = refTargetClass;
                if (target == null) {
                    throw new IllegalStateException(
                        "Целевой словарь ссылочного атрибута не определён");
                }
                return (SelectionForm) selectionFormAssembler.assemble(
                    (Class) target, (Consumer) onSelect);
            });
            bindExternal(new BindingDescriptor("enteredRefId", "Значение", false), refValueField,
                row -> snapshot(row.getAttrValue()),
                (row, value) -> {
                    if (value instanceof IdentifiableEntity entity) {
                        row.setEnteredRefId(entity.getId());
                    } else {
                        // очистка поля — снять и прежнее значение строки;
                        // «тихого» сохранения старого значения быть не должно
                        row.setEnteredRefId(null);
                        row.setAttrValue(null);
                    }
                },
                refValueField::getValue,
                value -> {
                    if (value == null) {
                        refValueField.clear();
                    } else {
                        refValueField.setValue(value);
                    }
                },
                value -> value == null,
                refValueField::setReadOnly);
        }
        return refValueField;
    }

    /** Тексты поиска для автокомплита ссылочного поля — как у обычного lookup-поля. */
    private String[] refSearchFields(Class<?> target) {
        return selectionFormAssembler.resolveColumns(target).columns().stream()
            .filter(path -> path.getResolvedType() == FieldType.TEXT)
            .map(ColumnPath::getKey)
            .toArray(String[]::new);
    }

    // === Валидация ===

    @Override
    public boolean isValid() {
        return validate().isEmpty();
    }

    @Override
    public List<String> validate() {
        List<String> errors = new ArrayList<>(super.validate());
        AttributeType type = attrTypeField.getValue();
        if (type == null) {
            // required-проверку самого поля «Тип атрибута» уже даёт metadata-биндинг
            return errors;
        }
        if (!type.isActive()) {
            errors.add("Атрибут «" + type.getDisplayName() + "» неактивен");
            return errors;
        }
        AttributeValueType valueType = type.getValueType() == null
            ? AttributeValueType.STRING : type.getValueType();
        switch (valueType) {
            case STRING, NUMBER -> {
                String text = textValueField == null ? null : textValueField.getValue();
                if (text == null || text.isBlank()) {
                    errors.add("Значение: обязательно для заполнения");
                }
            }
            case ENUM -> {
                if (enumValueField == null || enumValueField.getValue() == null) {
                    errors.add("Значение: выберите значение внутреннего справочника");
                }
            }
            case REF -> {
                if (refValueField == null || refValueField.getValue() == null) {
                    errors.add("Значение: выберите строку словаря");
                }
            }
        }
        return errors;
    }

    // === Черновик строки ===

    private static String initialText(NomAttributeValue row, AttributeType type) {
        if (row == null) {
            return "";
        }
        if (row.getEnteredValue() != null) {
            return row.getEnteredValue();
        }
        AttributeValue value = valueOfType(row, type);
        return value == null || value.getCode() == null ? "" : value.getCode();
    }

    private static AttributeValue valueOfType(NomAttributeValue row, AttributeType type) {
        if (row == null || row.getAttrValue() == null || type == null) {
            return null;
        }
        AttributeType valueType = row.getAttrValue().getAttrType();
        return valueType != null && valueType.getId() != null
            && valueType.getId().equals(type.getId())
            ? row.getAttrValue() : null;
    }

    private static HasDisplayName snapshot(AttributeValue value) {
        if (value == null || value.getRefId() == null) {
            return null;
        }
        return new RefSnapshot(value.getRefId(),
            value.getName() != null ? value.getName() : value.getCode());
    }

    private static Span hint(String text) {
        Span span = new Span(text);
        span.getStyle().set("color", "var(--lumo-secondary-text-color)");
        span.getStyle().set("font-size", "var(--lumo-font-size-s)");
        return span;
    }

    private static String shortClassName(String className) {
        if (className == null) {
            return "";
        }
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    /** Отображаемая строка словаря для уже сохранённого ссылочного значения (без lazy load). */
    private static final class RefSnapshot implements HasDisplayName, IdentifiableEntity {

        private Long id;
        private final String displayName;

        private RefSnapshot(Long id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public void setId(Long id) {
            this.id = id;
        }

        @Override
        public String getDisplayName() {
            return displayName == null ? "" : displayName;
        }
    }
}
