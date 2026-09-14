package org.ip.views.forms;

import org.ip.model.AttributeType;
import org.ip.model.Nomenclature;
import org.ip.service.NomSklAttributeService;
import org.ipro.crud.ServiceLocator;
import org.ipro.form.builder.ItemFormCustomization;
import org.ipro.form.builder.ItemFormVariants;
import org.ipro.metadata.EntityMetadataInfo;
import org.springframework.stereotype.Component;

/**
 * Регистрирует {@link NomenclatureItemForm} как default-вариант формы номенклатуры:
 * generic-поля справочника + секция «Атрибуты КСУ» (привязки «Номенклатура ↔ Тип атрибута»).
 *
 * <p>Волна E: справочник типов атрибутов читается canonical handle через {@link ServiceLocator},
 * а не {@code LookupService}: кастомизация формы не зависит от класса, чей API начинается с
 * «перечитать выбранное значение по ID» (ADX-07, {@code ApplicationFormFetchBoundaryTest}).</p>
 */
@Component
public class NomenclatureItemFormConfig implements ItemFormCustomization {

    private final ServiceLocator serviceLocator;

    public NomenclatureItemFormConfig(ServiceLocator serviceLocator) {
        this.serviceLocator = serviceLocator;
    }

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
            return new NomenclatureItemForm(meta, ctx.fieldFactory(), bindingService,
                serviceLocator.<AttributeType, Long>findService(AttributeType.class).findAll());
        });
    }
}
