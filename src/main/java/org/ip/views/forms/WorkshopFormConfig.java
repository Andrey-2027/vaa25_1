package org.ip.views.forms;

import org.ip.form.FieldFactory;
import org.ip.form.builder.ItemFormCustomization;
import org.ip.form.builder.ItemFormVariants;
import org.ip.metadata.EntityMetadataInfo;
import org.ip.metadata.MetadataResolver;
import org.ip.model.Workshop;
import org.springframework.stereotype.Component;

/**
 * Регистрирует {@link WorkshopItemForm} — полностью написанную вручную Форму Элемента — как
 * default-вариант для {@link Workshop}, через escape hatch
 * {@link ItemFormVariants#addDefaultCustom}, а не через дерево {@code ItemFormBuilder}.
 */
@Component
public class WorkshopFormConfig implements ItemFormCustomization {

    @Override
    public Class<?> entityClass() {
        return Workshop.class;
    }

    @Override
    public void configure(ItemFormVariants variants) {
        variants.addDefaultCustom(context -> {
            MetadataResolver metadataResolver = context.getParameter("metadataResolver");
            FieldFactory fieldFactory = context.getParameter("fieldFactory");
            EntityMetadataInfo meta = metadataResolver.resolve(Workshop.class);
            return new WorkshopItemForm(meta, fieldFactory);
        });
    }
}
