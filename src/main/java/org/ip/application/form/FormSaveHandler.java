package org.ip.application.form;

import org.ip.form.builtin.ItemForm;
import org.ipro.crud.IdentifiableEntity;

/**
 * Обработчик сохранения формы (спецификация «Часть C.1»).
 *
 * <p>Инкапсулирует сценарий сохранения (use case, адаптер агрегата или generic
 * сервис). Устанавливается на форму через {@code ItemForm.setSaveHandler(...)};
 * точка входа — {@code ItemForm.save()}, который сначала валидирует форму,
 * затем делегирует сюда.</p>
 *
 * <p>Реализация не должна закрывать форму/диалог — закрытием владеет host
 * (Dialog/Workspace), читающий {@link FormSaveResult}.</p>
 */
public interface FormSaveHandler<T extends IdentifiableEntity> {

    FormSaveResult<T> save(ItemForm<T> form);
}
