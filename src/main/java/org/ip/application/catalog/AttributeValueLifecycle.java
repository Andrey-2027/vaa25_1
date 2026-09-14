package org.ip.application.catalog;

import org.ip.model.AttributeValue;
import org.ip.model.AttributeValueType;
import org.ipro.crud.ValidationException;
import org.ipro.lifecycle.EntityLifecycle;
import org.ipro.lifecycle.EntitySaveContext;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Каноническая lifecycle-точка значения атрибута.
 *
 * <p>Нормализует служебный дедуп-ключ {@code codeUp} и держит соответствие
 * «{@code refId} ↔ тип «Ссылка»». Раньше это было кросс-полевое правило
 * {@code AttributeValueService.validateBusinessRules}, поэтому применялось только тем путём
 * сохранения, которым шёл сервис. Теперь правило и нормализация — часть canonical write
 * pipeline: она выполняется после capability-границы и раннего RLS, до persistence, для любого
 * канала записи. Нормализация остаётся обязательной и на «нормальном» пути: без неё уникальный
 * индекс {@code (attr_type_id, code_up)} не защитил бы от дублей в другом регистре.</p>
 */
@Component
public class AttributeValueLifecycle implements EntityLifecycle<AttributeValue> {

    @Override
    public Class<AttributeValue> entityType() {
        return AttributeValue.class;
    }

    @Override
    public void beforeSave(EntitySaveContext<AttributeValue> context) {
        AttributeValue entity = context.entity();
        if (entity.getRefId() != null) {
            if (entity.getAttrType() == null
                    || entity.getAttrType().getValueType() != AttributeValueType.REF) {
                throw new ValidationException(
                    "Ссылочное значение (refId) допустимо только для типа «Ссылка».");
            }
            entity.setCodeUp(null);
            return;
        }
        if (entity.getAttrType() != null
                && entity.getAttrType().getValueType() == AttributeValueType.REF) {
            throw new ValidationException(
                "Для типа «Ссылка» значение обязано ссылаться на строку словаря (refId).");
        }
        // пересчитываем служебный дедуп-ключ (поле не участвует в формах)
        if (entity.getCode() != null) {
            entity.setCodeUp(entity.getCode().toUpperCase(Locale.ROOT));
        }
    }
}
