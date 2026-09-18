package org.ip.views.forms;

import org.ip.model.AttributeType;
import org.ip.model.Nomenclature;
import org.ip.service.NomSklAttributeService;
import org.ipro.crud.EntityLookup;
import org.ipro.form.LookupComboHelper;
import org.ipro.form.builder.ItemFormCustomization;
import org.ipro.form.builder.ItemFormVariants;
import org.ipro.metadata.EntityMetadataInfo;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Регистрирует {@link NomenclatureItemForm} как default-вариант формы номенклатуры:
 * generic-поля справочника + секция «Атрибуты КСУ» (привязки «Номенклатура ↔ Тип атрибута»).
 *
 * <p>D3.5.2: типы атрибутов читаются bounded {@link EntityLookup#search} как маленький
 * закрытый справочник (десятки записей, явный лимит), а не выгрузкой всей таблицы.</p>
 */
@Component
public class NomenclatureItemFormConfig implements ItemFormCustomization {

    private final EntityLookup lookupService;
    private final NomSklAttributeService bindingService;

    public NomenclatureItemFormConfig(EntityLookup lookupService,
                                     NomSklAttributeService bindingService) {
        this.lookupService = lookupService;
        this.bindingService = bindingService;
    }

    @Override
    public Class<?> entityClass() {
        return Nomenclature.class;
    }

    @Override
    public void configure(ItemFormVariants variants) {
        variants.addDefault(ctx -> {
            EntityMetadataInfo meta = ctx.metadataResolver().resolve(Nomenclature.class);
            return new NomenclatureItemForm(meta, ctx.fieldFactory(), bindingService,
                lookupService.search(AttributeType.class, List.of("code", "name"), "",
                    LookupComboHelper.DICTIONARY_LIMIT));
        });
    }
}
