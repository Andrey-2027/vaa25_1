package org.ipro.form.registry;

import org.ipro.identity.IdentifiableEntity;

/**
 * Команда списка, зарегистрированная как Spring-бин для сущности.
 *
 * <p>Команда получает не только выбранную строку, но и снимок контекста ListForm:
 * параметры открытия и текущие контекстные фильтры. Это позволяет передавать связь
 * source → target без доступа к приватному состоянию формы.</p>
 *
 * <p>Для команды, используемой только одним составным View, отдельный бин не обязателен:
 * View может собрать кнопку локальным {@code createCommand()}. Этот интерфейс предназначен
 * для действий, переиспользуемых несколькими списками или вариантами.</p>
 */
public interface ListCommand<T extends IdentifiableEntity> {

    /** Сущность, в реестре которой команда показывается. */
    Class<T> entityClass();

    /** Подпись кнопки. */
    String title();

    /** Имя {@link com.vaadin.flow.component.icon.VaadinIcon} (null/неверное — без иконки). */
    default String iconName() {
        return null;
    }

    /** Активна только при наличии одной выбранной строки. */
    default boolean requiresSelection() {
        return false;
    }

    /**
     * Ограничить команду конкретным вариантом списка. По умолчанию команда доступна
     * во всех вариантах сущности.
     */
    default boolean appliesToVariant(String variant) {
        return true;
    }

    /** Дополнительная проверка доступности по строке и текущему контексту. */
    default boolean isEnabled(ListCommandContext<T> context) {
        return true;
    }

    /** Выполнить команду. */
    void execute(ListCommandContext<T> context);
}
