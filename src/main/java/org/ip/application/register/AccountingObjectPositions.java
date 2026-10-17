package org.ip.application.register;

import org.ip.model.AccountingObjectCorrection;
import org.ip.model.AccountingObjectCorrectionItem;
import org.ip.model.AccountingObjectDocument;
import org.ip.model.AccountingObjectInstallation;
import org.ip.model.AccountingObjectInstallationItem;
import org.ip.model.AccountingObjectItem;
import org.ipro.crud.AggregateCommandPayload;
import org.ipro.crud.ValidationException;
import org.ipro.lifecycle.EntitySaveContext;

import java.util.List;

/**
 * Позиции документа, объявленные командой, — единственный способ донести их до lifecycle-хуков
 * документа (ADR-0026).
 *
 * <p>Платформа объявляет payload на время операции и сама связывает с объявлением управляемую копию,
 * которую видит {@code onSave} при обновлении. Поэтому здесь нет ни ThreadLocal'а, ни сравнения
 * {@code id} и Hibernate-класса: то, что приложение знало о своей команде, объявляется один раз, а
 * тождество считает сторона, создавшая копию.</p>
 *
 * <p>Вето ({@link #require(EntitySaveContext, Class)}) вызывается из {@code beforeSave}, то есть до
 * persistence: сохранить документ мимо команды нельзя ни через generic-форму, ни через прямой
 * aggregate save — объявление открывает только команда.</p>
 */
final class AccountingObjectPositions {

    private static final String MESSAGE =
        "Сохраняйте документ объекта учёта вместе с его позициями через команду документа";

    private AccountingObjectPositions() {
    }

    /** Тип позиции документа по типу самого документа. */
    static Class<? extends AccountingObjectItem> itemType(Class<? extends AccountingObjectDocument> documentType) {
        if (documentType == AccountingObjectInstallation.class) {
            return AccountingObjectInstallationItem.class;
        }
        if (documentType == AccountingObjectCorrection.class) {
            return AccountingObjectCorrectionItem.class;
        }
        throw new ValidationException("Неподдерживаемый документ объекта учёта: "
            + documentType.getSimpleName());
    }

    /**
     * Позиции команды; сохранение вне команды, объявившей их, — ошибка. Пустой объявленный набор
     * отвергается наравне с его отсутствием: документ без позиций не имеет смысла ни для регистра,
     * ни для вето.
     */
    static <I extends AccountingObjectItem> List<I> require(EntitySaveContext<?> context, Class<I> itemType) {
        List<I> positions = AggregateCommandPayload.of(context)
            .filter(payload -> payload.hasSection(itemType))
            .map(payload -> payload.section(itemType))
            .orElseGet(List::of);
        if (positions.isEmpty()) {
            throw new ValidationException(MESSAGE);
        }
        return positions;
    }
}
