package org.ip.form.registry;

import com.vaadin.flow.component.Component;

/**
 * Фабрика для создания форм.
 *
 * Принимает FormContext (контекст вызова) и возвращает готовый компонент формы.
 *
 * Примеры использования:
 * <pre>
 * // Простая фабрика
 * FormFactory factory = context -> new CustomListForm(context);
 *
 * // С параметрами из контекста
 * FormFactory factory = context -> {
 *     Workshop workshop = context.getParameter("workshop");
 *     return new NomenclatureByWorkshopForm(workshop);
 * };
 * </pre>
 */
@FunctionalInterface
public interface FormFactory {
    /**
     * Создать компонент формы на основе контекста.
     *
     * @param context контекст вызова (параметры, родительская сессия и т.д.)
     * @return готовый компонент формы
     */
    Component create(FormContext context);
}
