package org.ipro.form.registry;

import org.ipro.crud.EntityLookup;
import org.ipro.form.FieldFactory;
import org.ipro.form.coordinator.FormNavigator;
import org.ipro.metadata.MetadataResolver;
import org.ipro.crud.BaseService;

import java.util.HashMap;
import java.util.Map;

/**
 * Контекст создания формы, передаётся в {@link FormFactory}.
 *
 * Инфраструктурные зависимости — обычные типизированные поля (не строковые ключи в карте):
 * опечатка в имени поля ловится компилятором, а не молчаливым NullPointerException
 * в рантайме. Карта {@code parameters} — только бизнес-параметры конкретного открытия
 * (например, "workshop", "journalId").
 *
 * <p>D3.5.1: concrete {@code LookupService} заменён интерфейсом {@link EntityLookup},
 * Spring {@code ApplicationContext} удалён (зависимости приходят в Spring-bean
 * customization через конструктор, а не через {@code ctx.getBean(...)} внутри
 * публичного UI API), строковый ключ {@code "coordinator"} заменён типизированным
 * {@link FormNavigator} (его подставляет сам координатор; резолвер о координаторе
 * не знает, поэтому цикла FormResolver → FormContext → FormCoordinator нет).</p>
 */
public class FormContext {
    private final Class<?> entityClass;
    private final Object id;
    private final MetadataResolver metadataResolver;
    private final FieldFactory fieldFactory;
    private final EntityLookup entityLookup;
    private final BaseService<?, ?> service;
    private final FormNavigator formNavigator;
    private final Map<String, Object> parameters;

    public FormContext(Class<?> entityClass, Object id,
                       MetadataResolver metadataResolver, FieldFactory fieldFactory,
                       EntityLookup entityLookup,
                       Map<String, Object> parameters) {
        this(entityClass, id, metadataResolver, fieldFactory, entityLookup, null, null, parameters);
    }

    public FormContext(Class<?> entityClass, Object id,
                       MetadataResolver metadataResolver, FieldFactory fieldFactory,
                       EntityLookup entityLookup,
                       BaseService<?, ?> service, FormNavigator formNavigator,
                       Map<String, Object> parameters) {
        this.entityClass = entityClass;
        this.id = id;
        this.metadataResolver = metadataResolver;
        this.fieldFactory = fieldFactory;
        this.entityLookup = entityLookup;
        this.service = service;
        this.formNavigator = formNavigator;
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

    public EntityLookup entityLookup() {
        return entityLookup;
    }

    /**
     * Сервис сущности (кладёт резолвер для List-фабрик). Null на путях, где сервис
     * не резолвится (View-фабрики координатора).
     */
    public BaseService<?, ?> service() {
        return service;
    }

    /**
     * Навигация форм (кладёт координатор). Null на путях, построенных резолвером
     * без координатора, и при ручном создании вне Spring.
     */
    public FormNavigator formNavigator() {
        return formNavigator;
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
        private EntityLookup entityLookup;
        private BaseService<?, ?> service;
        private FormNavigator formNavigator;
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

        public Builder entityLookup(EntityLookup entityLookup) {
            this.entityLookup = entityLookup;
            return this;
        }

        public Builder service(BaseService<?, ?> service) {
            this.service = service;
            return this;
        }

        public Builder formNavigator(FormNavigator formNavigator) {
            this.formNavigator = formNavigator;
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
            return new FormContext(entityClass, id, metadataResolver, fieldFactory, entityLookup,
                service, formNavigator, parameters);
        }
    }
}
