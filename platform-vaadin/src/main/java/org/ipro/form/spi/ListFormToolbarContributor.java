package org.ipro.form.spi;

import org.ipro.form.builtin.ListForm;

/**
 * Глобальная добавка в тулбар списков (для всех сущностей сразу).
 *
 * <p>В отличие от {@code ListCommand}-бинов (действие одной сущности) и кастомайзеров
 * (правка одного списка в его конфиге) — сквозная механика платформы: печать,
 * экспорт и т.п. Приложение регистрирует бины-реализации, координатор применяет
 * все найденные к каждому созданному списку.</p>
 */
public interface ListFormToolbarContributor {

    /**
     * @param form готовый список (generic или кастомный, уже с базовыми кнопками)
     * @param entityClass класс сущности списка
     * @param variant вариант списка (null = default)
     */
    void contribute(ListForm<?, ?> form, Class<?> entityClass, String variant);
}
