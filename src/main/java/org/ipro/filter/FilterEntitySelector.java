package org.ipro.filter;

import java.util.function.Consumer;

/**
 * Открывает диалог выбора сущности для ссылочного условия фильтра
 * (реализация — на стороне приложения через {@code SelectionFormAssembler}).
 *
 * <p>Компонент платформы не зависит от UI выбора (SelectionForm) напрямую;
 * вызывающий код предоставляет селектор. {@code onSelect} получает выбранную
 * сущность, её каноническое значение (displayName/toString) сохраняется в условии.</p>
 */
@FunctionalInterface
public interface FilterEntitySelector {

    /** Открывает форму выбора для {@code entityClass}; при выборе вызывает {@code onSelect}. */
    void open(Class<?> entityClass, Consumer<Object> onSelect);
}
