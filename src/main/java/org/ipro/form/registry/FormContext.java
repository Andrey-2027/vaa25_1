package org.ipro.form.registry;

import org.ipro.form.FieldFactory;
import org.ipro.metadata.MetadataResolver;
import org.ipro.crud.BaseService;
import org.ipro.crud.LookupService;
import org.springframework.context.ApplicationContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Контекст создания формы, передаётся в {@link FormFactory}.
 *
 * Инфраструктурные зависимости — обычные типизированные поля (не строковые ключи в карте):
 * опечатка в имени поля ловится компилятором, а не молчаливым NullPointerException
 * в рантайме. Карта {@code parameters} — только бизнес-параметры конкретного открытия
 * (например, "workshop"); исключение — ключ "coordinator", который подставляет сам
 * координатор: типизированное поле FormCoordinator здесь создало бы цикл
 * FormResolver → FormContext → FormCoordinator → FormResolver.
 */
public class FormContext {
    private final Class<?> entityClass;
    private final Object id;
    private final MetadataResolver metadataResolver;
    private final FieldFactory fieldFactory;
    private final LookupService lookupService;
    private final BaseService<?, ?> service;
    private final ApplicationContext applicationContext;
    private final Map<String, Object> parameters;

    public FormContext(Class<?> entityClass, Object id,
                       MetadataResolver metadataResolver, FieldFactory fieldFactory,
                       LookupService lookupService,
                       Map<String, Object> parameters) {
        this(entityClass, id, metadataResolver, fieldFactory, lookupService, null, null, parameters);
    }

    public FormContext(Class<?> entityClass, Object id,
                       MetadataResolver metadataResolver, FieldFactory fieldFactory,
                       LookupService lookupService,
                       BaseService<?, ?> service, ApplicationContext applicationContext,
                       Map<String, Object> parameters) {
        this.entityClass = entityClass;
        this.id = id;
        this.metadataResolver = metadataResolver;
        this.fieldFactory = fieldFactory;
        this.lookupService = lookupService;
        this.service = service;
        this.applicationContext = applicationContext;
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

    /**
     * Сервис сущности (кладёт резолвер для List-фабрик). Null на путях, где сервис
     * не резолвится (View-фабрики координатора).
     */
    public BaseService<?, ?> service() {
        return service;
    }

    /**
     * Spring-контекст (кладёт резолвер/координатор). Null, если недоступен
     * (ручное создание вне Spring).
     */
    public ApplicationContext applicationContext() {
        return applicationContext;
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
        private BaseService<?, ?> service;
        private ApplicationContext applicationContext;
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

        public Builder service(BaseService<?, ?> service) {
            this.service = service;
            return this;
        }

        public Builder applicationContext(ApplicationContext applicationContext) {
            this.applicationContext = applicationContext;
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
                service, applicationContext, parameters);
        }
    }
}
