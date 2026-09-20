package org.ipro.form;

import org.ipro.identity.IdentifiableEntity;
import org.ipro.form.builtin.ItemForm;


/**
 * Обработчик сохранения формы — участник реестра (roadmap, этап B3).
 *
 * <p>Реестр позволяет диспетчеру выбирать сценарий сохранения по классу сущности,
 * не зная о конкретных доменных типах: платформенный {@code ItemFormSaveDispatcher}
 * не содержит type switch. Каждый нестандартный агрегат со своей транзакцией
 * приносит собственный handler.</p>
 *
 * <p>Отличие от {@link FormSaveHandler}: последний — точка входа формы
 * ({@code ItemForm.setSaveHandler}), такая точка в приложении ровно одна; этот
 * интерфейс описывает участников реестра, которых может быть много. Поэтому
 * реализации регистрируются как {@code ItemFormSaveHandler}, но не как бины
 * {@code FormSaveHandler} — иначе {@code getBean(FormSaveHandler.class)} у
 * координатора стал бы неоднозначным.</p>
 */
public interface ItemFormSaveHandler<T extends IdentifiableEntity> {

    /**
     * Единственный явно объявленный тип, для которого предназначен handler.
     *
     * <p>Возврат типа позволяет registry проверить дубликаты до первого запроса.
     * Для legacy/dynamic handlers можно оставить {@code null} и переопределить
     * {@link #supports(Class)}; неоднозначность тогда будет отклонена в момент
     * разрешения, до persistence.</p>
     */
    default Class<T> supportedEntityClass() {
        return null;
    }

    /** Поддерживает ли обработчик формы указанную сущность. */
    default boolean supports(Class<?> entityClass) {
        Class<T> supported = supportedEntityClass();
        return supported != null && supported.equals(entityClass);
    }

    /** Проверка базового контракта для registry и custom implementations. */
    default void validateDeclaration() {
        Class<T> supported = supportedEntityClass();
        if (supported != null && !IdentifiableEntity.class.isAssignableFrom(supported)) {
            throw new IllegalArgumentException(
                "ItemFormSaveHandler declares non-entity type " + supported.getName());
        }
    }

    /**
     * Сохранить форму. Контракт тот же, что у {@link FormSaveHandler#save}:
     * исключения не подавляются (их оборачивает {@code ItemForm.save()}),
     * форму/диалог обработчик не закрывает.
     */
    FormSaveResult<T> save(ItemForm<T> form);
}
