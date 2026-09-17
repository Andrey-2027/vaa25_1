package org.ipro.crud.jpa;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.ipro.crud.BaseService;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.crud.ReferenceCheckService;
import org.ipro.crud.ValidationException;
import org.ipro.events.EntityEventPublisher;
import org.ipro.events.EventContext;
import org.ipro.events.EventSource;
import org.ipro.lifecycle.EntityLifecycleRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Минимальная валидирующая CRUD-база платформы (план reportstudio-reverse-deps, 2.3):
 * bean-валидация + reference-check на удаление, поверх JpaRepository.
 *
 * <p>Сознательно НЕ включает (в отличие от canonical data path, ADR-0007):
 * RLS write/read-policy и read-gate, нумерацию (@Numbered), metadata fetch-graphs,
 * UI-search по метаданным, конвенции имён бинов. Наследники добавляют своё
 * (см. ReportTemplateService). Идентификатор — Long ({@link IdentifiableEntity}).</p>
 */
@jakarta.transaction.Transactional
public class ValidatedJpaCrudService<T extends IdentifiableEntity> implements BaseService<T, Long> {

    protected final JpaRepository<T, Long> repository;
    private final Validator validator;
    private final ReferenceCheckService referenceCheckService;
    private final Class<T> domainClass;

    /**
     * Optional-бин контура entity events: база работает и без него (юнит-тесты собирают
     * сервис вручную), в приложении бин приходит из {@code EventsAutoConfiguration}.
     */
    @Autowired
    private Optional<EntityEventPublisher> entityEventPublisher = Optional.empty();

    @Autowired
    private Optional<EntityLifecycleRegistry> entityLifecycleRegistry = Optional.empty();

    protected ValidatedJpaCrudService(JpaRepository<T, Long> repository, Validator validator,
                                      ReferenceCheckService referenceCheckService) {
        this.repository = repository;
        this.validator = validator;
        this.referenceCheckService = referenceCheckService;
        this.domainClass = resolveDomainClass();
        requireInternalStore();
    }

    /**
     * ADR-0007 §3: {@code ValidatedJpaCrudService} — internal-store adapter для
     * non-metadata storage подсистемы (шаблоны отчётов). Metadata-driven root обязан
     * идти через canonical path, поэтому такая подмена — ошибка конфигурации при старте,
     * а не тихо потерянные RLS, fetch-план и events.
     *
     * <p>Проверка по факту аннотации типа, а не по документации: правило закрыто в самом
     * классе, и никакой новый сервис не может обойти его случайно.</p>
     */
    private void requireInternalStore() {
        if (domainClass.isAnnotationPresent(
                org.ipro.metadata.annotation.EntityMetadata.class)) {
            throw new IllegalStateException(getClass().getSimpleName() + " extends"
                + " ValidatedJpaCrudService<" + domainClass.getSimpleName()
                + ">, but that type is metadata-driven (@EntityMetadata)."
                + " Metadata-driven roots must use the canonical data path"
                + " (ADR-0007 §3); ValidatedJpaCrudService is only an internal-store"
                + " adapter for non-metadata storage.");
        }
    }

    @Override
    public T save(T entity) {
        validate(entity);
        Optional<T> original = originalForUpdate(entity);
        EntityEventPublisher.EventScope eventScope = openEntityEventOperation(entity);
        try {
            publishSaving(entity);
            publishUpdating(original, entity);
            T saved = repository.save(entity);
            publishSaved(saved);
            return saved;
        } finally {
            closeEventScope(eventScope);
        }
    }

    /**
     * Veto-capable entity-событие перед persistence: доменное правило, оформленное
     * слушателем {@code EntitySavingEvent}, применяется к любому пути сохранения
     * (generic CRUD, UI-форма, будущий REST), а не только к UI-экрану.
     *
     * <p>Source = {@link EventSource#SYSTEM}: канал вызова сервису неизвестен —
     * агрегатные операции публикуют своё событие из use case с явным source.</p>
     */
    protected void publishSaving(T entity) {
        EventContext context = lifecycleContext(entity, "save:" + domainClass().getSimpleName());
        entityEventPublisher.ifPresent(publisher -> publisher.publishSaving(entity, context));
        entityLifecycleRegistry.ifPresent(registry -> registry.beforeSave(
            domainClass(), entity, context));
    }

    /** Вызвать typed callback только для существующей записи с доступным исходным состоянием. */
    protected void publishUpdating(Optional<T> original, T updated) {
        if (original.isEmpty()) {
            return;
        }
        EventContext context = lifecycleContext(updated,
            "update:" + domainClass().getSimpleName());
        entityLifecycleRegistry.ifPresent(registry -> registry.beforeUpdate(
            domainClass(), original.get(), updated, context));
    }

    /** Публикует Saved внутри транзакции и Changed после commit. */
    protected void publishSaved(T entity) {
        EventContext context = lifecycleContext(entity, "save:" + domainClass().getSimpleName());
        entityEventPublisher.ifPresent(publisher -> {
            if (publisher.isAggregateOperation(domainClass())) {
                return;
            }
            publisher.publishSaved(entity, context);
            publisher.publishChanged(entity, context);
        });
        entityLifecycleRegistry.ifPresent(registry -> {
            if (entityEventPublisher.isEmpty()
                || !entityEventPublisher.get().isAggregateOperation(domainClass())) {
                registry.onSave(domainClass(), entity, context);
            }
        });
    }

    /** Синхронный veto перед удалением. */
    protected void publishDeleting(T entity) {
        EventContext context = lifecycleContext(entity, "delete:" + domainClass().getSimpleName());
        entityEventPublisher.ifPresent(publisher -> publisher.publishDeleting(entity, context));
        entityLifecycleRegistry.ifPresent(registry -> registry.beforeDelete(
            domainClass(), entity, context));
    }

    /** Планирует факт удаления после успешного commit. */
    protected void publishDeleted(T entity) {
        entityEventPublisher.ifPresent(publisher -> publisher.publishDeleted(entity,
            publisher.contextFor(domainClass(), entity.getId(), EventSource.SYSTEM,
                "delete:" + domainClass().getSimpleName())));
    }

    private EntityEventPublisher.EventScope openEntityEventOperation(T entity) {
        if (entityEventPublisher.isEmpty()) {
            return null;
        }
        EntityEventPublisher publisher = entityEventPublisher.get();
        if (publisher.isAggregateOperation(domainClass())) {
            return null;
        }
        EventContext context = publisher.contextFor(domainClass(), entity.getId(),
            EventSource.SYSTEM, "entity:" + domainClass().getSimpleName());
        return publisher.openOperation(context);
    }

    private static void closeEventScope(EntityEventPublisher.EventScope eventScope) {
        if (eventScope != null) {
            eventScope.close();
        }
    }

    private EventContext lifecycleContext(T entity, String operationName) {
        return entityEventPublisher
            .map(publisher -> publisher.contextFor(
                domainClass(), entity.getId(), EventSource.SYSTEM, operationName))
            .orElseGet(() -> EventContext.forEntity(
                domainClass(), entity.getId(), EventSource.SYSTEM, operationName));
    }

    private Optional<T> originalForUpdate(T entity) {
        if (entity.getId() == null) {
            return Optional.empty();
        }
        return repository.findById(entity.getId());
    }

    @Override
    public T create(T entity) {
        return save(entity);
    }

    @Override
    public T update(T entity) {
        return save(entity);
    }

    /**
     * Удаление защищено проверкой ссылочной целостности: если на запись есть ссылки
     * из других сущностей, удаление блокируется ({@link ReferenceCheckService}).
     */
    @Override
    public void delete(Long id) {
        referenceCheckService.checkNoReferences(domainClass(), id);
        Optional<T> existing = repository.findById(id);
        if (existing.isEmpty()) {
            repository.deleteById(id);
            return;
        }

        T entity = existing.get();
        EntityEventPublisher.EventScope eventScope = openEntityEventOperation(entity);
        try {
            publishDeleting(entity);
            repository.deleteById(id);
            publishDeleted(entity);
        } finally {
            closeEventScope(eventScope);
        }
    }

    @Override
    public Optional<T> findById(Long id) {
        return repository.findById(id);
    }

    @Override
    public List<T> findAll() {
        return repository.findAll();
    }

    @Override
    public Page<T> findAll(Pageable pageable) {
        return repository.findAll(pageable);
    }

    /**
     * UI-search здесь не реализован: это база internal-store, а не standard path. Стандартные
     * сущности ищутся через canonical search engine
     * ({@code CanonicalReadExecutor.readSearch}, C4.4); для internal-store переопределяйте
     * предметным поиском.
     */
    @Override
    public List<T> search(String term) {
        throw new UnsupportedOperationException(
                "search(String) not implemented for " + getClass().getSimpleName());
    }

    @Override
    public Page<T> search(String term, Pageable pageable) {
        throw new UnsupportedOperationException(
                "search(String, Pageable) not implemented for " + getClass().getSimpleName());
    }

    /** Домен-класс дженерика — для reference-check удаления. */
    protected Class<T> domainClass() {
        return domainClass;
    }

    protected void validate(T entity) {
        Set<ConstraintViolation<T>> violations = validator.validate(entity);
        if (!violations.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (ConstraintViolation<T> violation : violations) {
                sb.append(violation.getPropertyPath())
                        .append(": ")
                        .append(violation.getMessage())
                        .append("\n");
            }
            throw new ValidationException(sb.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private Class<T> resolveDomainClass() {
        return (Class<T>) ((ParameterizedType) getClass().getGenericSuperclass())
                .getActualTypeArguments()[0];
    }
}
