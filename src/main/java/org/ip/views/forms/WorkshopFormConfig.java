package org.ip.views.forms;

import org.ipro.form.builder.ItemFormCustomization;
import org.ipro.form.builder.ItemFormVariants;
import org.ipro.metadata.EntityMetadataInfo;
import org.ip.model.Workshop;
import org.springframework.stereotype.Component;

/**
 * Регистрирует {@link WorkshopItemForm} — полностью написанную вручную Форму Элемента — как
 * default-вариант для {@link Workshop}.
 */
@Component
public class WorkshopFormConfig implements ItemFormCustomization {

    @Override
    public Class<?> entityClass() {
        return Workshop.class;
    }

    @Override
    public void configure(ItemFormVariants variants) {
        variants.addDefault(ctx -> {
            EntityMetadataInfo meta = ctx.metadataResolver().resolve(Workshop.class);
            return new WorkshopItemForm(meta, ctx.fieldFactory());
        });
    }
}
