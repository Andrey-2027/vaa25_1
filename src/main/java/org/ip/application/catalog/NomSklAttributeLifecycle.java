package org.ip.application.catalog;

import org.ip.model.NomSklAttribute;
import org.ipro.crud.ValidationException;
import org.ipro.lifecycle.EntityLifecycle;
import org.ipro.lifecycle.EntitySaveContext;
import org.springframework.stereotype.Component;

/**
 * Каноническая lifecycle-точка привязки «Номенклатура ↔ Атрибут КСУ».
 *
 * <p>C4.6 волна E: правило переехало из {@code NomSklAttributeService.validateBusinessRules},
 * потому что сервис больше не наследует compatibility base. Теперь правило применяется к любому
 * пути записи этого типа, а не только к тому, которым шёл сервис: привязка не существует без
 * сохранённой позиции (уникальный ключ пары включает {@code nomenclature_id}).</p>
 */
@Component
public class NomSklAttributeLifecycle implements EntityLifecycle<NomSklAttribute> {

    @Override
    public Class<NomSklAttribute> entityType() {
        return NomSklAttribute.class;
    }

    @Override
    public void beforeSave(EntitySaveContext<NomSklAttribute> context) {
        NomSklAttribute entity = context.entity();
        if (entity.getNomenclature() != null && entity.getNomenclature().getId() == null) {
            throw new ValidationException(
                "Привязка атрибута КСУ сохраняется только вместе с сохранённой позицией.");
        }
    }
}
