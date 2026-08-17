package org.ip.form.registry;

import org.ip.form.FieldFactory;
import org.ip.metadata.MetadataResolver;
import org.ip.service.LookupService;

import java.util.HashMap;
import java.util.Map;

/**
 * Контекст создания формы, передаётся в {@link FormFactory}.
 *
 * metadataResolver/fieldFactory — обычные типизированные поля (не строковые ключи в карте):
 * это единственные две зависимости, которые нужны практически любой FormFactory для сборки
 * ItemForm/ListForm, и опечатка в имени поля тут ловится компилятором, а не молчаливым
 * NullPointerException в рантайме (см. обсуждение — раньше это были context.getParameter(
 * "metadataResolver")/("fieldFactory"), и как минимум ListFormBuilder держал точно такой же
 * код на "applicationContext"/"service", которые FormResolver никогда не клал в контекст —
 * то есть тот путь был мёртв и просто не был замечен).
 *
 * lookupService — типизированный доступ к {@link LookupService} для фабрик, которым после
 * выбора сущности в UI-компоненте нужно перечитать её с нужным fetch-графом (вне сессии
 * ленивые прокси недоступны — см. PrdSpecMtrFormCustomization).
 *
 * parameters — произвольные бизнес-параметры конкретного открытия формы (например,
 * "workshop" — для какого цеха открываем), задаются вызывающим кодом через
 * FormCoordinator.openXxxForm(..., parameters) и не имеют отношения к инфраструктуре формы.
 */
public class FormContext {
    private final Class<?> entityClass;
    private final Object id;
    private final MetadataResolver metadataResolver;
    private final FieldFactory fieldFactory;
    private final LookupService lookupService;
    private final Map<String, Object> parameters;

    public FormContext(Class<?> entityClass, Object id,
                       MetadataResolver metadataResolver, FieldFactory fieldFactory,
                       LookupService lookupService,
                       Map<String, Object> parameters) {
        this.entityClass = entityClass;
        this.id = id;
        this.metadataResolver = metadataResolver;
        this.fieldFactory = fieldFactory;
        this.lookupService = lookupService;
        this.parameters = parameters != null ? new HashMap<>(parameters) : new HashMap<>();
    }

    public Class<?> getEntityClass() {
        return entityClass;
    }

    public Object getId() {
        return id;
    }

    public MetadataResolver metadataResolver() {
        return metadataResolver;
    }

    public FieldFactory fieldFactory() {
        return fieldFactory;
    }

    public LookupService lookupService() {
        return lookupService;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    /** Произвольный бизнес-параметр открытия формы (не инфраструктура) — например, "workshop". */
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

    // === Builder для удобного создания ===

    public static Builder builder(Class<?> entityClass) {
        return new Builder(entityClass);
    }

    public static class Builder {
        private final Class<?> entityClass;
        private Object id;
        private MetadataResolver metadataResolver;
        private FieldFactory fieldFactory;
        private LookupService lookupService;
        private Map<String, Object> parameters = new HashMap<>();

        private Builder(Class<?> entityClass) {
            this.entityClass = entityClass;
        }

        public Builder id(Object id) {
            this.id = id;
            return this;
        }

        public Builder metadataResolver(MetadataResolver metadataResolver) {
            this.metadataResolver = metadataResolver;
            return this;
        }

        public Builder fieldFactory(FieldFactory fieldFactory) {
            this.fieldFactory = fieldFactory;
            return this;
        }

        public Builder lookupService(LookupService lookupService) {
            this.lookupService = lookupService;
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

        public FormContext build() {
            return new FormContext(entityClass, id, metadataResolver, fieldFactory, lookupService,
                parameters);
        }
    }
}
