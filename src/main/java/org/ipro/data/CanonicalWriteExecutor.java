package org.ipro.data;

import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.ipro.crud.BaseEntity;
import org.ipro.crud.GenericOwnedSectionService;
import org.ipro.crud.IdentifiableEntity;
import org.ipro.crud.ReferenceCheckService;
import org.ipro.crud.ValidationException;
import org.ipro.events.EntityEventPublisher;
import org.ipro.events.EventContext;
import org.ipro.events.EventSource;
import org.ipro.lifecycle.EntityLifecycleRegistry;
import org.ipro.numbering.NumberingService;
import org.ipro.rls.RlsPolicyEnforcer;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Единый canonical write pipeline (C4, ADR-0007 §5):
 *
 * <pre>
 * write intent
 * -&gt; capability типа (fail-closed)
 * -&gt; ранняя RLS authorization
 * -&gt; нормализация версии
 * -&gt; нумерация
 * -&gt; server validation
 * -&gt; lifecycle before-hooks и before-events
 * -&gt; persistence
 * -&gt; after-events / lifecycle onSave
 * </pre>
 *
 * <p>Это тот же порядок, что у compatibility-пути {@code AbstractBaseService}: запрещённая
 * операция останавливается до валидации, хуков и событий, а repository-aspect и flush-listener
 * остаются последним рубежом для прямых вызовов repository. В отличие от compatibility-пути,
 * здесь <b>не нужен ни Spring Data repository, ни application service</b>: persistence идёт
 * через {@link EntityManager}, поэтому тип проходит CRUD, не имея ни repository, ни сервиса.</p>
 *
 * <p>Policy не дублируется: RLS, валидация, нумерация, lifecycle, события и aggregate
 * boundary — те же компоненты, что обслуживают compatibility-путь. Executor владеет только
 * порядком.</p>
 *
 * <p>Capability проверяется как enforcement-граница: write intent без разрешения отклоняется
 * до RLS и до пользовательского кода. Тип с намеренно запрещённой операцией (например,
 * {@code AttributeValue} — только create) отказывает здесь, а не глубоко в generic-вызове.</p>
 */
public class CanonicalWriteExecutor {

    private final EntityDescriptorCatalog catalog;
    private final CanonicalReadExecutor readExecutor;
    private final EntityManager entityManager;
    private final Validator validator;
    private final RlsPolicyEnforcer rlsPolicyEnforcer;
    private final NumberingService numberingService;
    private final EntityEventPublisher eventPublisher;
    private final EntityLifecycleRegistry lifecycleRegistry;
    private final GenericOwnedSectionService ownedSectionService;
    private final ReferenceCheckService referenceCheckService;

    public CanonicalWriteExecutor(EntityDescriptorCatalog catalog,
                                  CanonicalReadExecutor readExecutor,
                                  EntityManager entityManager,
                                  Validator validator,
                                  RlsPolicyEnforcer rlsPolicyEnforcer,
                                  NumberingService numberingService,
                                  EntityEventPublisher eventPublisher,
                                  EntityLifecycleRegistry lifecycleRegistry,
                                  GenericOwnedSectionService ownedSectionService,
                                  ReferenceCheckService referenceCheckService) {
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.readExecutor = Objects.requireNonNull(readExecutor, "readExecutor must not be null");
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
        // Optional policy owners: slice-контексты и hand-built тесты не обязаны иметь весь
        // контур. null означает лишь отсутствие соответствующей policy, а не ослабление
        // capability-границы — она вычисляется по descriptor'у и обязательна всегда.
        this.rlsPolicyEnforcer = rlsPolicyEnforcer;
        this.numberingService = numberingService;
        this.eventPublisher = eventPublisher;
        this.lifecycleRegistry = lifecycleRegistry;
        this.ownedSectionService = ownedSectionService;
        this.referenceCheckService = referenceCheckService;
    }

    /** Descriptor типа — для диагностики вызывающих. */
    public EntityDescriptor descriptorOf(Class<?> type) {
        return catalog.descriptorOf(type);
    }

    @Transactional
    public <T extends IdentifiableEntity> T create(Class<T> type, T entity) {
        return persist(type, entity, DataOperation.CREATE);
    }

    @Transactional
    public <T extends IdentifiableEntity> T update(Class<T> type, T entity) {
        return persist(type, entity, DataOperation.UPDATE);
    }

    /** Create или update по наличию id — единый intent UI-формы. */
    @Transactional
    public <T extends IdentifiableEntity> T save(Class<T> type, T entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        DataOperation operation = entity.getId() == null
            ? DataOperation.CREATE : DataOperation.UPDATE;
        return persist(type, entity, operation);
    }

    @Transactional
    public <T extends IdentifiableEntity> void delete(Class<T> type, Object id) {
        requireCapability(type, DataOperation.DELETE);
        if (id == null) {
            return;
        }
        Optional<T> existing = readExecutor.readDetail(DetailRead.of(type, id));
        if (existing.isEmpty()) {
            // Если protected-тип вернул пусто, это «отфильтровано», а не «не существует»:
            // превращать это в удаление по id нельзя — размерности недоступны для enforcement,
            // и repository-aspect не смог бы проверить право.
            return;
        }
        T entity = existing.get();
        authorizeDelete(entity);
        EntityEventPublisher.EventScope scope = openOperation(entity);
        try {
            publishDeleting(entity);
            if (ownedSectionService != null) {
                ownedSectionService.deleteAllOwnedSections(entity);
            }
            if (referenceCheckService != null) {
                referenceCheckService.checkNoReferences(type, id);
            }
            entityManager.remove(entityManager.contains(entity) ? entity : entityManager.merge(entity));
            publishDeleted(entity);
        } finally {
            close(scope);
        }
    }

    private <T extends IdentifiableEntity> T persist(Class<T> type, T entity,
                                                    DataOperation operation) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(entity, "entity must not be null");

        // Capability — первая enforcement-граница (ADR-0007 §2): проверяется по заявленной
        // паре (type, operation), поэтому диагностика запрета не подменяется проверкой
        // состояния объекта.
        requireCapability(type, operation);

        // Заявленный тип и persistence-класс объекта обязаны совпадать: иначе capability
        // берётся у одного типа, а persist/lifecycle/RLS работают с другим, и граница
        // проверяется не по тому объекту, который пишется.
        Class<?> persistenceClass = entityType(entity);
        if (!type.equals(persistenceClass)) {
            throw new IllegalArgumentException("Заявленный тип " + type.getName()
                + " не соответствует persistence-классу " + persistenceClass.getName());
        }

        // Intent решает, какая JPA-операция выполняется, а не наличие id: иначе create
        // существующей записи проходил бы CREATE-capability и делал merge (запрещённый
        // update), а update без id — insert.
        boolean isNew = entity.getId() == null;
        if (operation == DataOperation.CREATE && !isNew) {
            throw new IllegalStateException("create(" + type.getSimpleName()
                + ") получил сущность с id=" + entity.getId()
                + ": это существующее состояние — нужен intent UPDATE и capability UPDATE");
        }
        if (operation == DataOperation.UPDATE && isNew) {
            throw new IllegalStateException("update(" + type.getSimpleName()
                + ") получил сущность без id: это новое состояние, требуется create"
                + " и capability CREATE");
        }

        Optional<T> original = Optional.empty();
        if (operation == DataOperation.UPDATE) {
            original = originalForUpdate(entity);
            if (original.isEmpty()) {
                // Одинаковое сообщение для отсутствующей и RLS-скрытой строки не раскрывает,
                // существует ли недоступный пользователю id. UPDATE не должен превращаться
                // в INSERT через семантику EntityManager.merge().
                throw new IllegalStateException("Нельзя обновить " + type.getSimpleName()
                    + " id=" + entity.getId() + ": запись отсутствует или недоступна");
            }
            // Проверяем обе стороны переноса состояния. Проверка только payload позволила бы
            // заменить RLS-измерения и перезаписать строку, которую пользователь не может
            // изменять в её исходном состоянии.
            authorizeUpdate(original.get());
        }
        authorizeUpdate(entity);
        normalizeVersion(entity);
        assignNumbers(entity);
        validate(entity);
        EntityEventPublisher.EventScope scope = openOperation(entity);
        try {
            publishSaving(entity);
            publishUpdating(original, entity);
            T saved = isNew ? persistNew(entity) : entityManager.merge(entity);
            publishSaved(saved);
            return saved;
        } finally {
            close(scope);
        }
    }

    private <T extends IdentifiableEntity> T persistNew(T entity) {
        entityManager.persist(entity);
        return entity;
    }

    /**
     * Capability типа — enforcement-граница (ADR-0007 §2): пара {@code (type, operation)}
     * без разрешения отклоняется до RLS, до persistence и до пользовательского кода.
     */
    private void requireCapability(Class<?> type, DataOperation operation) {
        EntityDescriptor descriptor = catalog.descriptorOf(type);
        if (descriptor.capabilities().allows(operation)) {
            return;
        }
        if (descriptor.exposure() == EntityExposure.OWNED_ROW) {
            throw new IllegalStateException(type.getSimpleName()
                + " — строка owned-секции и не имеет автономного " + operation
                + "-handle. Секция изменяется только aggregate boundary владельца ("
                + descriptor.reason() + ").");
        }
        throw new IllegalStateException(type.getSimpleName() + " не имеет canonical "
            + operation + "-handle: " + descriptor.exposure() + " («" + descriptor.reason()
            + "», policy: " + descriptor.capabilities().reason() + ").");
    }

    /** Ранний write-гейт: до валидации, хуков и before-событий. */
    private void authorizeUpdate(Object entity) {
        if (rlsPolicyEnforcer != null && entity != null) {
            rlsPolicyEnforcer.requireUpdate(entity);
        }
    }

    private void authorizeDelete(Object entity) {
        if (rlsPolicyEnforcer != null && entity != null) {
            rlsPolicyEnforcer.requireDelete(entity);
        }
    }

    /**
     * null-версия при id != null — состояние строк, созданных до появления {@code @Version}.
     * Нормализуем null → 0, иначе Hibernate видит противоречие «версия новая, id сохранённый».
     */
    private void normalizeVersion(Object entity) {
        if (entity instanceof BaseEntity base && base.getId() != null && base.getVersion() == null) {
            base.setVersion(0L);
        }
    }

    private void assignNumbers(Object entity) {
        if (entity instanceof IdentifiableEntity identifiable
                && identifiable.getId() != null) {
            return;
        }
        if (numberingService != null) {
            numberingService.assignAutoValues(entity);
        }
    }

    private void validate(Object entity) {
        @SuppressWarnings("unchecked")
        Set<ConstraintViolation<Object>> violations = validator.validate(entity);
        if (violations.isEmpty()) {
            return;
        }
        StringBuilder message = new StringBuilder();
        for (ConstraintViolation<Object> violation : violations) {
            message.append(violation.getPropertyPath())
                .append(": ")
                .append(violation.getMessage())
                .append('\n');
        }
        throw new ValidationException(message.toString());
    }

    private <T extends IdentifiableEntity> Optional<T> originalForUpdate(T entity) {
        if (entity.getId() == null) {
            return Optional.empty();
        }
        @SuppressWarnings("unchecked")
        Class<T> type = (Class<T>) entityType(entity);
        return readExecutor.readDetail(DetailRead.of(type, entity.getId()));
    }

    private <T extends IdentifiableEntity> EntityEventPublisher.EventScope openOperation(T entity) {
        if (eventPublisher == null) {
            return null;
        }
        Class<?> type = entityType(entity);
        if (eventPublisher.isAggregateOperation(type)) {
            return null;
        }
        EventContext context = eventPublisher.contextFor(type, entity.getId(),
            EventSource.SYSTEM, "entity:" + type.getSimpleName());
        return eventPublisher.openOperation(context);
    }

    private void publishSaving(Object entity) {
        EventContext context = lifecycleContext(entity, "save:");
        if (eventPublisher != null) {
            eventPublisher.publishSaving(entity, context);
        }
        if (lifecycleRegistry != null) {
            invokeLifecycleBeforeSave(entity, context);
        }
    }

    private void publishUpdating(Optional<?> original, Object updated) {
        if (original.isEmpty() || lifecycleRegistry == null) {
            return;
        }
        EventContext context = lifecycleContext(updated, "update:");
        invokeLifecycleBeforeUpdate(original.get(), updated, context);
    }

    private void publishSaved(Object entity) {
        EventContext context = lifecycleContext(entity, "save:");
        Class<?> type = entityType(entity);
        if (eventPublisher != null) {
            if (!eventPublisher.isAggregateOperation(type)) {
                eventPublisher.publishSaved(entity, context);
                eventPublisher.publishChanged(entity, context);
            }
        }
        if (lifecycleRegistry != null) {
            boolean aggregate = eventPublisher != null && eventPublisher.isAggregateOperation(type);
            if (!aggregate) {
                invokeLifecycleOnSave(entity, context);
            }
        }
    }

    private void publishDeleting(Object entity) {
        EventContext context = lifecycleContext(entity, "delete:");
        if (eventPublisher != null) {
            eventPublisher.publishDeleting(entity, context);
        }
        if (lifecycleRegistry != null) {
            invokeLifecycleBeforeDelete(entity, context);
        }
    }

    private void publishDeleted(Object entity) {
        if (eventPublisher == null) {
            return;
        }
        Class<?> type = entityType(entity);
        eventPublisher.publishDeleted(entity, eventPublisher.contextFor(type,
            ((IdentifiableEntity) entity).getId(), EventSource.SYSTEM,
            "delete:" + type.getSimpleName()));
    }

    private EventContext lifecycleContext(Object entity, String operationPrefix) {
        Class<?> type = entityType(entity);
        Object id = entity instanceof IdentifiableEntity identifiable ? identifiable.getId() : null;
        String operation = operationPrefix + type.getSimpleName();
        if (eventPublisher != null) {
            return eventPublisher.contextFor(type, id, EventSource.SYSTEM, operation);
        }
        return EventContext.forEntity(type, id, EventSource.SYSTEM, operation);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void invokeLifecycleBeforeSave(Object entity, EventContext context) {
        lifecycleRegistry.beforeSave((Class) entityType(entity), (IdentifiableEntity) entity, context);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void invokeLifecycleBeforeUpdate(Object original, Object updated, EventContext context) {
        lifecycleRegistry.beforeUpdate((Class) entityType(updated), (IdentifiableEntity) original,
            (IdentifiableEntity) updated, context);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void invokeLifecycleOnSave(Object entity, EventContext context) {
        lifecycleRegistry.onSave((Class) entityType(entity), (IdentifiableEntity) entity, context);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void invokeLifecycleBeforeDelete(Object entity, EventContext context) {
        lifecycleRegistry.beforeDelete((Class) entityType(entity), (IdentifiableEntity) entity,
            context);
    }

    private static void close(EntityEventPublisher.EventScope scope) {
        if (scope != null) {
            scope.close();
        }
    }

    /** Реальный persistence class без инициализации Hibernate proxy. */
    private static Class<?> entityType(Object entity) {
        if (entity instanceof org.hibernate.proxy.HibernateProxy proxy) {
            return proxy.getHibernateLazyInitializer().getPersistentClass();
        }
        return entity.getClass();
    }

    /** Список разрешённых write-намерений типа — для диагностики и тестов. */
    public Set<DataOperation> allowedWrites(Class<?> type) {
        return catalog.descriptorOf(type).capabilities().writes();
    }

    /** Список разрешённых read-сценариев типа — для диагностики и тестов. */
    public List<org.ipro.fetch.plan.FetchScenario> allowedReads(Class<?> type) {
        return List.copyOf(catalog.descriptorOf(type).capabilities().readScenarios());
    }
}
