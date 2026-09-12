package org.ip.application.catalog;

import org.ip.model.AttributeType;
import org.ip.model.AttributeValue;
import org.ip.model.NomAttributeValue;
import org.ip.service.AttributeValueService;
import org.ipro.crud.ValidationException;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Предметное правило строки «Атрибут — значение»: ввод формы превращается в строку
 * единого словаря {@link AttributeValue}, а связка «тип атрибута ↔ значение» проверяется
 * на сервере.
 *
 * <p>Правило вызывается из двух границ — aggregate save шапки номенклатуры
 * ({@code NomenclatureLifecycle.beforeAggregateSave}) и обычного CRUD самой строки
 * ({@code NomAttributeValueService}). UI передаёт только ввод формы
 * ({@code enteredValue}/{@code enteredRefId}); нормализация и поиск или создание строки
 * словаря выполняются в текущей транзакции, поэтому откат сохранения шапки откатывает
 * и созданную строку словаря ({@code AttributeValueService.getOrCreateInCurrentTransaction}).
 *
 * <p>Правила по типам значения:
 * <ul>
 *   <li>{@code STRING} — свободный ввод, дедуп без учёта регистра;</li>
 *   <li>{@code NUMBER} — свободный ввод с канонической формой («1,5»/«1.50» → «1.5»);</li>
 *   <li>{@code ENUM} — только существующее значение внутреннего справочника типа
 *       (карточка номенклатуры не пополняет словарь типа);</li>
 *   <li>{@code REF} — выбранная строка целевого словаря типа ({@code enteredRefId}).</li>
 * </ul>
 */
@Component
public class NomAttributeValueValueRule {

    private final AttributeValueService attributeValueService;

    public NomAttributeValueValueRule(AttributeValueService attributeValueService) {
        this.attributeValueService = Objects.requireNonNull(attributeValueService,
            "attributeValueService must not be null");
    }

    /**
     * Разрешить ввод формы в строку словаря и проверить связку «тип ↔ значение».
     *
     * @throws ValidationException если атрибут не выбран/неактивен, значение не задано
     *                             или не принадлежит выбранному типу
     */
    public void resolve(NomAttributeValue row) {
        AttributeType type = requireActiveType(row);
        switch (type.getValueType()) {
            case STRING -> resolveScalar(row, type, false);
            case NUMBER -> resolveScalar(row, type, true);
            case ENUM -> resolveEnum(row, type);
            case REF -> resolveReference(row, type);
        }
        requireValueOfType(row, type);
    }

    private AttributeType requireActiveType(NomAttributeValue row) {
        AttributeType type = row.getAttrType();
        if (type == null) {
            throw new ValidationException("Не выбран атрибут номенклатуры.");
        }
        if (type.getId() == null) {
            throw new ValidationException("Атрибут «" + type.getDisplayName()
                + "» не сохранён: значение можно указать только для существующего атрибута.");
        }
        if (!type.isActive()) {
            throw new ValidationException("Атрибут «" + type.getDisplayName()
                + "» неактивен: значение по нему указать нельзя.");
        }
        return type;
    }

    /**
     * STRING/NUMBER: свободный ввод, при пустом вводе сохраняется уже установленное значение.
     * Если значение установлено, но принадлежит другому типу (пользователь сменил тип
     * существующей строки), сообщается несоответствие типа, а не «не заполнено».
     */
    private void resolveScalar(NomAttributeValue row, AttributeType type, boolean numeric) {
        String entered = normalized(row.getEnteredValue());
        if (entered.isEmpty()) {
            AttributeValue existing = row.getAttrValue();
            if (belongsToType(existing, type)) {
                return;
            }
            if (existing != null) {
                throw valueOfAnotherType(existing, type);
            }
            throw new ValidationException("Не заполнено значение атрибута «"
                + type.getDisplayName() + "».");
        }
        AttributeValue resolved = numeric
            ? attributeValueService.getOrCreateInCurrentTransaction(
                type, AttributeValueService.parseNumber(entered))
            : attributeValueService.getOrCreateInCurrentTransaction(type, entered);
        row.setAttrValue(resolved);
    }

    /** ENUM: значение выбирается из внутреннего справочника типа, а не создаётся карточкой. */
    private void resolveEnum(NomAttributeValue row, AttributeType type) {
        if (row.getAttrValue() == null) {
            throw new ValidationException("Для атрибута «" + type.getDisplayName()
                + "» (внутренний справочник) выберите значение.");
        }
    }

    /**
     * REF: выбранная строка целевого словаря превращается в ссылочное значение словаря.
     * Как и у скаляров, установленное значение другого типа сообщается как несоответствие
     * типа, а не как «не выбран словарь».
     */
    private void resolveReference(NomAttributeValue row, AttributeType type) {
        Long refId = row.getEnteredRefId();
        if (refId == null) {
            AttributeValue existing = row.getAttrValue();
            if (belongsToType(existing, type)) {
                return;
            }
            if (existing != null) {
                throw valueOfAnotherType(existing, type);
            }
            throw new ValidationException("Для атрибута «" + type.getDisplayName()
                + "» выберите строку словаря.");
        }
        Class<?> targetClass = attributeValueService.resolveTargetDictionary(type);
        row.setAttrValue(attributeValueService.getOrCreateRefInCurrentTransaction(
            type, targetClass, refId));
    }

    private void requireValueOfType(NomAttributeValue row, AttributeType type) {
        AttributeValue value = row.getAttrValue();
        if (value == null) {
            throw new ValidationException("Не указано значение атрибута «"
                + type.getDisplayName() + "».");
        }
        if (!belongsToType(value, type)) {
            throw valueOfAnotherType(value, type);
        }
    }

    /** Единственная формулировка несоответствия «тип ↔ значение» для всех веток правила. */
    private static ValidationException valueOfAnotherType(AttributeValue value, AttributeType type) {
        return new ValidationException("Значение «" + value.getDisplayName()
            + "» не принадлежит типу «" + type.getDisplayName() + "».");
    }

    private static boolean belongsToType(AttributeValue value, AttributeType type) {
        return value != null
            && value.getAttrType() != null
            && value.getAttrType().getId() != null
            && value.getAttrType().getId().equals(type.getId());
    }

    private static String normalized(String raw) {
        return raw == null ? "" : raw.trim();
    }
}
