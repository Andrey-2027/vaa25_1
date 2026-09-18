package org.ip.views.forms;

import org.ip.model.NomAttributeValue;
import org.ip.service.AttributeValueService;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.form.builder.ItemFormCustomization;
import org.ipro.form.builder.ItemFormVariants;
import org.ipro.metadata.FieldMetadataInfo;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Регистрирует форму строки «Атрибут — значение» как вариант {@link NomAttributeValue}:
 * поле значения зависит от выбранного типа атрибута (строка/число/внутренний
 * справочник/ссылка).
 *
 * <p>Это единственный вариант, который табличная часть номенклатуры открывает для строки
 * (см. {@link NomAttributeValueTableCustomization}). Справочник «Атрибуты номенклатуры»
 * продолжает использовать обычную форму из metadata: там строка выбирает уже
 * существующие значения словаря, и самостоятельное создание значений из карточки
 * номенклатуры не происходит.
 */
@Component
public class NomAttributeValueFormConfig implements ItemFormCustomization {

    /** Ключ варианта формы строки, который выбирает табличная часть номенклатуры. */
    public static final String TYPE_DEPENDENT_VARIANT = "attributeValue";

    private final AttributeValueService attributeValueService;
    private final SelectionFormAssembler selectionFormAssembler;

    public NomAttributeValueFormConfig(AttributeValueService attributeValueService,
                                      SelectionFormAssembler selectionFormAssembler) {
        this.attributeValueService = attributeValueService;
        this.selectionFormAssembler = selectionFormAssembler;
    }

    @Override
    public Class<?> entityClass() {
        return NomAttributeValue.class;
    }

    @Override
    public void configure(ItemFormVariants variants) {
        variants.add(TYPE_DEPENDENT_VARIANT, ctx -> {
            List<FieldMetadataInfo> fields = ctx.metadataResolver()
                .resolveRowMetadata(NomAttributeValue.class)
                .getFormFields().stream()
                .filter(field -> "attrType".equals(field.getName()))
                .toList();
            return new NomAttributeValueItemForm(fields, ctx.fieldFactory(),
                attributeValueService, ctx.entityLookup(), selectionFormAssembler);
        });
    }
}
