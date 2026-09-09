package org.ip.views.forms;

import org.ip.model.SklNomOpa;
import org.ip.service.SklNomOpaService;
import org.ipro.form.builder.ItemFormCustomization;
import org.ipro.form.builder.ItemFormVariants;
import org.ipro.metadata.EntityMetadataInfo;
import org.springframework.stereotype.Component;

/**
 * Регистрирует {@link SklNomOpaForm} как default-вариант формы набора атрибутов КСУ:
 * generic-поля шапки (только просмотр) + секция строк «тип → значение», кнопка «Отмена».
 */
@Component
public class SklNomOpaFormConfig implements ItemFormCustomization {

    @Override
    public Class<?> entityClass() {
        return SklNomOpa.class;
    }

    @Override
    public void configure(ItemFormVariants variants) {
        variants.addDefault(ctx -> {
            EntityMetadataInfo meta = ctx.metadataResolver().resolve(SklNomOpa.class);
            SklNomOpaService setService = ctx.applicationContext().getBean(SklNomOpaService.class);
            return new SklNomOpaForm(meta, ctx.fieldFactory(), setService);
        });
    }
}
