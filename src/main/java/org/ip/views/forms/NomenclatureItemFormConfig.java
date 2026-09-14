package org.ip.views.forms;

import org.ip.model.AttributeType;
import org.ip.model.Nomenclature;
import org.ip.service.NomSklAttributeService;
import org.ipro.form.builder.ItemFormCustomization;
import org.ipro.form.builder.ItemFormVariants;
import org.ipro.metadata.EntityMetadataInfo;
import org.springframework.stereotype.Component;

/**
 * Регистрирует {@link NomenclatureItemForm} как default-вариант формы номенклатуры:
 * generic-поля справочника + секция «Атрибуты КСУ» (привязки «Номенклатура ↔ Тип атрибута»).
 */
@Component
public class NomenclatureItemFormConfig implements ItemFormCustomization {

    @Override
    public Class<?> entityClass() {
        return Nomenclature.class;
    }

    @Override
    public void configure(ItemFormVariants variants) {
        variants.addDefault(ctx -> {
            EntityMetadataInfo meta = ctx.metadataResolver().resolve(Nomenclature.class);
            NomSklAttributeService bindingService =
                ctx.applicationContext().getBean(NomSklAttributeService.class);
            // Справочник типов атрибутов — только для выбора в диалоге привязки: canonical
            // lookup вместо типизированного сервиса (C4.6 волна E), как роли в UserFormConfig.
            return new NomenclatureItemForm(meta, ctx.fieldFactory(),
                bindingService, ctx.lookupService().findAll(AttributeType.class));
        });
    }
}
