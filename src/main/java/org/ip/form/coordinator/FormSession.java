package org.ip.form.coordinator;

import java.util.HashMap;
import java.util.Map;

/**
 * Сессия открытой формы. Отслеживает контекст и связи между формами.
 *
 * Используется FormCoordinator для управления цепочками вызовов:
 *   ListForm → ItemForm → SelectionForm → ItemForm (создание на лету)
 *
 * Поля:
 *   - sessionId    — уникальный идентификатор сессии (UUID)
 *   - entityClass  — класс сущности (Nomenclature.class)
 *   - mode         — режим отображения (LIST, ITEM, SELECTION)
 *   - parent       — родительская сессия (кто открыл эту форму)
 *   - context      — параметры (entityId, фильтры, выбранное значение)
 */
public record FormSession(
    String sessionId,
    Class<?> entityClass,
    SessionMode mode,
    FormSession parent,
    Map<String, Object> context
) {
    /**
     * Конструктор с пустым контекстом.
     */
    public FormSession(String sessionId, Class<?> entityClass, SessionMode mode, FormSession parent) {
        this(sessionId, entityClass, mode, parent, new HashMap<>());
    }

    /**
     * Получить значение из контекста.
     */
    public Object get(String key) {
        return context.get(key);
    }

    /**
     * Положить значение в контекст.
     */
    public void put(String key, Object value) {
        context.put(key, value);
    }
}
