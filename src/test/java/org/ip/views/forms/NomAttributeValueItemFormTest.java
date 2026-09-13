package org.ip.views.forms;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.textfield.TextField;
import org.ip.model.AttributeType;
import org.ip.model.AttributeValue;
import org.ip.model.AttributeValueType;
import org.ip.model.NomAttributeValue;
import org.ip.model.Workshop;
import org.ip.service.AttributeValueService;
import org.ipro.crud.LookupService;
import org.ipro.form.EntityField;
import org.ipro.form.FieldFactory;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.metadata.FieldMetadataInfo;
import org.ipro.metadata.MetadataResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Контракт формы строки табличной части «Атрибуты номенклатуры» (без Spring и без UI-сессии).
 *
 * <p>Проверяется то, чего не выражают metadata: вид поля значения определяется выбранным
 * {@link AttributeType#getValueType()}, ввод уходит в черновик строки
 * ({@code enteredValue}/{@code enteredRefId}), а строка словаря {@link AttributeValue}
 * формой не создаётся — это работа сервера в транзакции сохранения номенклатуры.</p>
 *
 * <p>Состав полей формы в тестах берётся тем же выражением, что и в
 * {@link NomAttributeValueFormConfig}: поля строки из metadata, отфильтрованные до
 * {@code attrType}. Поэтому проверка «в диалоге нет поля родителя» относится именно
 * к тому, что реально открывает табличная часть.</p>
 */
class NomAttributeValueItemFormTest {

    private AttributeValueService attributeValueService;
    private LookupService lookupService;
    private SelectionFormAssembler selectionFormAssembler;

    @BeforeEach
    void setUp() {
        attributeValueService = mock(AttributeValueService.class);
        lookupService = mock(LookupService.class);
        selectionFormAssembler = mock(SelectionFormAssembler.class);
        // FieldFactory резолвит колонки lookup-поля сразу при создании EntityField.
        when(selectionFormAssembler.resolveColumns(AttributeType.class))
            .thenReturn(new SelectionFormAssembler.ResolvedSelection(List.of(), "Типы атрибутов"));
    }

    @Test
    void stringValueIsPrefilledFromDictionaryAndEditedAsDraft() {
        AttributeType type = type(11L, "COLOR", "Цвет", AttributeValueType.STRING);
        NomAttributeValueItemForm form = form();

        form.setEntity(new NomAttributeValue(null, type, value(type, "XL")));

        TextField valueField = (TextField) form.getField("enteredValue");
        assertThat(valueField.getValue()).isEqualTo("XL");
        // Открытие существующей строки не является изменением: поле значения заполняется
        // уже после metadata-загрузки, и снимок для isDirty() переставляется заново.
        assertThat(form.isDirty()).isFalse();

        valueField.setValue("XXL");

        assertThat(form.isDirty()).isTrue();
        assertThat(form.getEntity().getEnteredValue()).isEqualTo("XXL");
    }

    @Test
    void numberValueUsesTextInputWithNumericLabel() {
        AttributeType type = type(12L, "LEN", "Длина", AttributeValueType.NUMBER);
        NomAttributeValueItemForm form = form();

        form.setEntity(new NomAttributeValue(null, type, value(type, "1.5")));

        assertThat(flatten(form))
            .filteredOn(c -> c instanceof TextField tf && "Значение (число)".equals(tf.getLabel()))
            .singleElement()
            .satisfies(c -> assertThat(((TextField) c).getValue()).isEqualTo("1.5"));
    }

    @Test
    void enumValueIsSelectedFromDictionaryOfTheType() {
        AttributeType type = type(13L, "MAT", "Материал", AttributeValueType.ENUM);
        AttributeValue option = value(type, "STEEL");
        when(attributeValueService.findByAttrType(type)).thenReturn(List.of(option));
        NomAttributeValueItemForm form = form();

        form.setEntity(new NomAttributeValue(null, type, null));

        ComboBox<AttributeValue> combo = enumField(form);
        assertThat(combo.getListDataView().getItemCount()).isEqualTo(1);
        assertThat(combo.getListDataView().getItem(0)).isSameAs(option);

        combo.setValue(option);

        // Значение словаря выбирается, а не создаётся: словарь не пополняется карточкой строки.
        assertThat(form.getEntity().getAttrValue()).isSameAs(option);
        assertThat(form.validate()).isEmpty();
        org.mockito.Mockito.verify(attributeValueService, org.mockito.Mockito.never())
            .getOrCreateInCurrentTransaction(any(), anyString());
    }

    @Test
    void refValueIsChosenFromTargetDictionaryOfTheType() {
        AttributeType type = type(14L, "MAT_REF", "Материал (ссылка)", AttributeValueType.REF);
        type.setTargetDictionary(Workshop.class.getName());
        doReturn(Workshop.class).when(attributeValueService).resolveTargetDictionary(type);
        NomAttributeValueItemForm form = form();

        form.setEntity(new NomAttributeValue(null, type, null));

        EntityField<org.ipro.metadata.HasDisplayName> field = form.entityField("enteredRefId");
        assertThat(field.getLabel()).contains("Workshop");

        Workshop workshop = new Workshop("W-1", "Цех 1");
        workshop.setId(7L);
        field.setValue(workshop);

        // REF-значение уходит черновиком по id строки словаря: сама строка — общая.
        assertThat(form.getEntity().getEnteredRefId()).isEqualTo(7L);
    }

    @Test
    void savedRefValueIsShownWithoutLoadingTargetEntity() {
        AttributeType type = type(15L, "MAT_SAVED", "Материал (сохранённый)", AttributeValueType.REF);
        type.setTargetDictionary(Workshop.class.getName());
        doReturn(Workshop.class).when(attributeValueService).resolveTargetDictionary(type);
        AttributeValue saved = new AttributeValue(type, "Сталь 20", "Сталь 20", null, 9L);
        NomAttributeValueItemForm form = form();

        form.setEntity(new NomAttributeValue(null, type, saved));

        EntityField<org.ipro.metadata.HasDisplayName> field = form.entityField("enteredRefId");
        assertThat(field.getValue()).isNotNull();
        assertThat(field.getValue().getDisplayName()).isEqualTo("Сталь 20");
        assertThat(form.validate()).isEmpty();
    }

    @Test
    void clearingRefFieldDropsBothDraftIdAndStoredValue() {
        AttributeType type = type(16L, "MAT_CLEAR", "Материал (очистка)", AttributeValueType.REF);
        type.setTargetDictionary(Workshop.class.getName());
        doReturn(Workshop.class).when(attributeValueService).resolveTargetDictionary(type);
        AttributeValue saved = new AttributeValue(type, "Сталь 20", "Сталь 20", null, 9L);
        NomAttributeValueItemForm form = form();
        form.setEntity(new NomAttributeValue(null, type, saved));

        form.entityField("enteredRefId").clear();

        // Очистка поля не должна «тихо» сохранить прежнее значение строки.
        assertThat(form.getEntity().getAttrValue()).isNull();
        assertThat(form.getEntity().getEnteredRefId()).isNull();
    }

    @Test
    void missingAttributeShowsHintAndFailsRequiredValidation() {
        NomAttributeValueItemForm form = form();

        form.setEntity(new NomAttributeValue());

        assertThat(flatten(form))
            .filteredOn(c -> c instanceof Span s && s.getText().startsWith("Выберите атрибут"))
            .hasSize(1);
        assertThat(form.validate()).anySatisfy(error -> assertThat(error).contains("Тип атрибута"));
        // Поля значения нет вовсе: пока тип не выбран, вводить нечего.
        assertThat(valueTextFields(form)).isEmpty();
    }

    @Test
    void inactiveAttributeBlocksValueInput() {
        AttributeType type = type(17L, "OLD", "Устаревший", AttributeValueType.STRING);
        type.setActive(false);
        NomAttributeValueItemForm form = form();

        form.setEntity(new NomAttributeValue(null, type, null));

        assertThat(flatten(form))
            .filteredOn(c -> c instanceof Span s && s.getText().contains("неактивен"))
            .hasSize(1);
        assertThat(form.validate()).anySatisfy(error -> assertThat(error).contains("неактивен"));
        assertThat(valueTextFields(form)).isEmpty();
    }

    @Test
    void validationRequiresValueAccordingToAttributeType() {
        AttributeType stringType = type(18L, "S", "Строковый", AttributeValueType.STRING);
        assertThat(formWith(stringType).validate())
            .contains("Значение: обязательно для заполнения");

        AttributeType enumType = type(19L, "E", "Перечислимый", AttributeValueType.ENUM);
        when(attributeValueService.findByAttrType(enumType)).thenReturn(List.of());
        assertThat(formWith(enumType).validate())
            .contains("Значение: выберите значение внутреннего справочника");

        AttributeType refType = type(20L, "R", "Ссылочный", AttributeValueType.REF);
        refType.setTargetDictionary(Workshop.class.getName());
        doReturn(Workshop.class).when(attributeValueService).resolveTargetDictionary(refType);
        assertThat(formWith(refType).validate())
            .contains("Значение: выберите строку словаря");
    }

    @Test
    void rowDialogCarriesNoParentFieldBinding() {
        NomAttributeValueItemForm form = form();

        // Поле родителя остаётся в metadata строки (нужно автономному списку и серверным
        // путям), но в диалог строки не попадает: секция уже внутри своей позиции.
        assertThat(attrTypeFields()).extracting(FieldMetadataInfo::getName).containsExactly("attrType");
        assertThatThrownBy(() -> form.getField("nomenclature"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void changingAttributeTypeRebuildsValueInput() {
        AttributeType stringType = type(21L, "S2", "Строковый 2", AttributeValueType.STRING);
        AttributeType enumType = type(22L, "E2", "Перечислимый 2", AttributeValueType.ENUM);
        AttributeValue option = value(enumType, "STEEL");
        when(attributeValueService.findByAttrType(enumType)).thenReturn(List.of(option));
        when(lookupService.search(eq(AttributeType.class), any(String[].class), anyString(), anyInt()))
            .thenReturn(List.of(enumType));
        NomAttributeValueItemForm form = form();
        form.setEntity(new NomAttributeValue(null, stringType, value(stringType, "XL")));
        assertThat(valueTextFields(form)).hasSize(1);

        // Пользователь меняет атрибут: единственное совпадение по введённому тексту
        // (штатный путь EntityField) обязано переключить поле значения на тип нового атрибута.
        internalTextField(form.entityField("attrType")).setValue(enumType.getDisplayName());

        assertThat(form.entityField("attrType").getValue()).isSameAs(enumType);
        assertThat(valueTextFields(form)).isEmpty();
        assertThat(enumField(form).getListDataView().getItemCount()).isEqualTo(1);
    }

    // === Вспомогательное ===

    /** Те же поля строки, что передаёт табличной части {@link NomAttributeValueFormConfig}. */
    private static List<FieldMetadataInfo> attrTypeFields() {
        return new MetadataResolver().resolveRowMetadata(NomAttributeValue.class)
            .getFormFields().stream()
            .filter(field -> "attrType".equals(field.getName()))
            .toList();
    }

    private NomAttributeValueItemForm form() {
        return new NomAttributeValueItemForm(attrTypeFields(),
            new FieldFactory(lookupService, selectionFormAssembler),
            attributeValueService, lookupService, selectionFormAssembler);
    }

    private NomAttributeValueItemForm formWith(AttributeType type) {
        NomAttributeValueItemForm form = form();
        form.setEntity(new NomAttributeValue(null, type, null));
        return form;
    }

    private static AttributeType type(Long id, String code, String name, AttributeValueType valueType) {
        AttributeType type = new AttributeType(code, name, valueType);
        type.setId(id);
        return type;
    }

    private static AttributeValue value(AttributeType type, String code) {
        return new AttributeValue(type, code, code, code.toUpperCase(), null);
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<AttributeValue> enumField(NomAttributeValueItemForm form) {
        return (ComboBox<AttributeValue>) form.getField("attrValue");
    }

    /** TextField'ы ввода значения (не внутренние поля lookup-компонентов — у тех подпись пуста). */
    private static List<TextField> valueTextFields(NomAttributeValueItemForm form) {
        return flatten(form).stream()
            .filter(c -> c instanceof TextField tf && tf.getLabel() != null && !tf.getLabel().isEmpty())
            .map(TextField.class::cast)
            .toList();
    }

    /** Внутренний ввод EntityField — через него пользователь печатает код/наименование. */
    private static TextField internalTextField(EntityField<?> field) {
        return flatten(field).stream()
            .filter(TextField.class::isInstance)
            .map(TextField.class::cast)
            .findFirst()
            .orElseThrow(() -> new AssertionError("Внутри EntityField нет поля ввода"));
    }

    private static List<Component> flatten(Component root) {
        List<Component> result = new ArrayList<>();
        root.getChildren().forEach(child -> {
            result.add(child);
            result.addAll(flatten(child));
        });
        return result;
    }
}
