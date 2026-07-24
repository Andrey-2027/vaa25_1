package org.ip.form.registry;

import org.ip.form.coordinator.FormSession;

import java.util.HashMap;
import java.util.Map;

/**
 * Контекст создания формы.
 *
 * Содержит:
 *   - entityClass — класс сущности
 *   - id — ID записи (для ItemForm, может быть null при создании новой)
 *   - parameters — произвольные параметры (например, workshop, filters)
 *   - parentSession — родительская сессия (для цепочек форм)
 *
 * Используется FormFactory при создании кастомных форм.
 */
public class FormContext {
    private final Class<?> entityClass;
    private final Object id;
    private final Map<String, Object> parameters;
    private final FormSession parentSession;

    public FormContext(Class<?> entityClass, Object id, Map<String, Object> parameters, FormSession parentSession) {
        this.entityClass = entityClass;
        this.id = id;
        this.parameters = parameters != null ? new HashMap<>(parameters) : new HashMap<>();
        this.parentSession = parentSession;
    }

    public Class<?> getEntityClass() {
        return entityClass;
    }

    public Object getId() {
        return id;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    @SuppressWarnings("unchecked")
    public <T> T getParameter(String key) {
        return (T) parameters.get(key);
    }

    public <T> T getParameter(String key, T defaultValue) {
        T value = getParameter(key);
        return value != null ? value : defaultValue;
    }

    public boolean hasParameter(String key) {
        return parameters.containsKey(key);
    }

    public FormSession getParentSession() {
        return parentSession;
    }

    // === Builder для удобного создания ===

    public static Builder builder(Class<?> entityClass) {
        return new Builder(entityClass);
    }

    public static class Builder {
        private final Class<?> entityClass;
        private Object id;
        private Map<String, Object> parameters = new HashMap<>();
        private FormSession parentSession;

        private Builder(Class<?> entityClass) {
            this.entityClass = entityClass;
        }

        public Builder id(Object id) {
            this.id = id;
            return this;
        }

        public Builder parameter(String key, Object value) {
            this.parameters.put(key, value);
            return this;
        }

        public Builder parameters(Map<String, Object> parameters) {
            if (parameters != null) {
                this.parameters.putAll(parameters);
            }
            return this;
        }

        public Builder parentSession(FormSession parentSession) {
            this.parentSession = parentSession;
            return this;
        }

        public FormContext build() {
            return new FormContext(entityClass, id, parameters, parentSession);
        }
    }
}
