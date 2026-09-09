package org.ip.views.forms;

import org.ip.model.AttributeType;
import org.ip.service.AttributeValueService;
import org.ipro.form.builder.ItemFormCustomization;
import org.ipro.form.builder.ItemFormVariants;
import org.ipro.metadata.EntityMetadataInfo;
import org.springframework.stereotype.Component;

/**
 * Регистрирует {@link AttributeTypeForm} как default-вариант формы типа атрибута:
 * generic-поля справочника + секция «Значения» (управление значениями ENUM).
 */
@Component
public class AttributeTypeFormConfig implements ItemFormCustomization {

    @Override
    public Class<?> entityClass() {
        return AttributeType.class;
    }

    @Override
    public void configure(ItemFormVariants variants) {
        variants.addDefault(ctx -> {
            EntityMetadataInfo meta = ctx.metadataResolver().resolve(AttributeType.class);
            AttributeValueService valueService =
                ctx.applicationContext().getBean(AttributeValueService.class);
            return new AttributeTypeForm(meta, ctx.fieldFactory(), valueService);
        });
    }
}