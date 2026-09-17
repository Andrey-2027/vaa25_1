package org.ipro.data;

import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.ipro.crud.BaseEntity;
import org.ipro.crud.GenericOwnedSectionService;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.crud.ReferenceCheckService;
import org.ipro.crud.ValidationException;
import org.ipro.events.EntityEventPublisher;
import org.ipro.events.EventContext;
import org.ipro.events.EventSource;
import org.ipro.lifecycle.EntityLifecycleRegistry;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.TableSectionMetadataInfo;
import org.ipro.numbering.NumberingService;
import org.ipro.rls.RlsPolicyEnforcer;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
 * <p>Запрещённая операция останавливается до валидации, хуков и событий, а repository-aspect и
 * flush-listener остаются последним рубежом для прямых вызовов repository. При этом здесь
 * <b>не нужен ни Spring Data repository, ни application service</b>: persistence идёт
 * через {@link EntityManager}, поэтому тип проходит CRUD, не имея ни repository, ни сервиса.</p>
 *
 * <p>Policy не дублируется: RLS, валидация, нумерация, lifecycle, события и aggregate
 * boundary — те же компоненты, что обслуживают compatibility-путь. Executor владеет только
 * порядком.</p>
 *
 * <p>Capability проверяется как enforcement-граница: write intent без разрешения отклоняется
 * до RLS и до пользовательского кода. Тип с намеренно запрещённой операцией (например,
 * {@code AttributeValue} — только create) отказывает здесь, а не глубоко в generic-вызове.</p>
 *
 * <p>Отказ policy выражен типом {@link CanonicalWriteDeniedException} (capability, aggregate
 * boundary) и security-исключением ({@link AccessDeniedException}); всё остальное — ошибка
 * исполнения. Телеметрия записи двухфазная: {@code pipelineCompleted} фиксируется после
 * flush внутри операции, а {@code committed}/{@code rolledBack} — по исходу транзакции,
 * потому что Spring коммитит уже после возврата метода.</p>
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
    private final SectionMetadataRegistry sectionMetadataRegistry;
    private final WriteTelemetry writeTelemetry;

    /**
     * C4.8: глубина вложенных canonical write в одной бизнес-операции. Пока внешний scope
     * открыт (например, aggregate save вызвал {@code save}), вложенный вызов не открывает
     * второй scope — одна бизнес-операция даёт одну запись telemetry.
     */
    private static final ThreadLocal<Integer> WRITE_TELEMETRY_DEPTH =
        ThreadLocal.withInitial(() -> 0);

    public CanonicalWriteExecutor(EntityDescriptorCatalog catalog,
                                  CanonicalReadExecutor readExecutor,
                                  EntityManager entityManager,
                                  Validator validator,
                                  RlsPolicyEnforcer rlsPolicyEnforcer,
                                  NumberingService numberingService,
                                  EntityEventPublisher eventPublisher,
                                  EntityLifecycleRegistry lifecycleRegistry,
                                  GenericOwnedSectionService ownedSectionService,
                                  ReferenceCheckService referenceCheckService,
                                  SectionMetadataRegistry sectionMetadataRegistry) {
        this(catalog, readExecutor, entityManager, validator, rlsPolicyEnforcer,
            numberingService, eventPublisher, lifecycleRegistry, ownedSectionService,
            referenceCheckService, sectionMetadataRegistry, null);
    }

    public CanonicalWriteExecutor(EntityDescriptorCatalog catalog,
                                  CanonicalReadExecutor readExecutor,
                                  EntityManager entityManager,
                                  Validator validator,
                                  RlsPolicyEnforcer rlsPolicyEnforcer,
                                  NumberingService numberingService,
                                  EntityEventPublisher eventPublisher,
                                  EntityLifecycleRegistry lifecycleRegistry,
                                  GenericOwnedSectionService ownedSectionService,
                                  ReferenceCheckService referenceCheckService,
                                  SectionMetadataRegistry sectionMetadataRegistry,
                                  WriteTelemetry writeTelemetry) {
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
        // Optional metadata source: slice-контексты без registry сохраняют прежнее
        // поведение, а полный контекст получает aggregate-boundary guard.
        this.sectionMetadataRegistry = sectionMetadataRegistry;
        this.writeTelemetry = writeTelemetry == null ? WriteTelemetry.noop() : writeTelemetry;
    }

    /** Descriptor типа — для диагностики вызывающих. */
    public EntityDescriptor descriptorOf(Class<?> type) {
        return catalog.descriptorOf(type);
    }

    @Transactional
    public <T extends IdentifiableEntity> T create(Class<T> type, T entity) {
        return instrumented(DataOperation.CREATE, type, () -> {
            requireIntentCoversSections(type, DataOperation.CREATE);
            return persist(type, entity, DataOperation.CREATE);
        });
    }

    @Transactional
    public <T extends IdentifiableEntity> T update(Class<T> type, T entity) {
        return instrumented(DataOperation.UPDATE, type, () -> {
            requireIntentCoversSections(type, DataOperation.UPDATE);
            return persist(type, entity, DataOperation.UPDATE);
        });
    }

    /** Create или update по наличию id — единый intent UI-формы. */
    @Transactional
    public <T extends IdentifiableEntity> T save(Class<T> type, T entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        DataOperation operation = entity.getId() == null
            ? DataOperation.CREATE : DataOperation.UPDATE;
        return instrumented(operation, type, () -> persist(type, entity, operation));
    }

    @Transactional
    public <T extends IdentifiableEntity> void delete(Class<T> type, Object id) {
        instrumented(DataOperation.DELETE, type, () -> {
            requireCapability(type, DataOperation.DELETE);
            if (id == null) {
                return false;
            }
            Optional<T> existing = readExecutor.readDetail(DetailRead.of(type, id));
            if (existing.isEmpty()) {
                // Если protected-тип вернул пусто, это «отфильтровано», а не «не существует»:
                // превращать это в удаление по id нельзя — размерности недоступны для enforcement,
                // и repository-aspect не смог бы проверить право.
                return false;
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
            return true;
        }, removed -> removed ? 1 : 0);
    }

    /**
     * C4.8: единая telemetry-обвязка public write-intent'ов. Scope открывается до проверки
     * capability, поэтому ранний отказ фиксируется как {@code denied} с видом из
     * {@link WriteTelemetry.DenialKind}, а ошибка исполнения — как {@code failed}.
     * Вложенный canonical write в рамках одной бизнес-операции переиспользует внешний scope,
     * а не открывает второй.
     *
     * <p>Исход двухфазный: успех pipeline сообщается после flush (ошибки БД принадлежат этой
     * операции, а не «коммиту после возврата»), а факт коммита или отката — от synchronization
     * транзакции. Без наблюдаемой транзакции (hand-built executor без прокси) сообщается только
     * pipeline-фаза: выдать её за commit-исход нечем.</p>
     */
    private <T> T instrumented(DataOperation operation, Class<?> type,
                               java.util.function.Supplier<T> action) {
        return instrumented(operation, type, action, ignored -> 1);
    }

    private <T> T instrumented(DataOperation operation, Class<?> type,
                               java.util.function.Supplier<T> action,
                               java.util.function.ToIntFunction<T> affectedRows) {
        boolean outermost = WRITE_TELEMETRY_DEPTH.get() == 0;
        WriteTelemetry.WriteScope scope = outermost
            ? writeTelemetry.begin(operation, type) : null;
        WRITE_TELEMETRY_DEPTH.set(WRITE_TELEMETRY_DEPTH.get() + 1);
        try {
            T result = action.get();
            if (scope != null) {
                // Flush принадлежит этой операции: констрейнт, уникальность или ошибка
                // нумерации обязаны попасть в исход операции, а не в commit после возврата.
                entityManager.flush();
                scope.pipelineCompleted(affectedRows.applyAsInt(result));
                registerTransactionOutcome(scope);
            }
            return result;
        } catch (CanonicalWriteDeniedException denied) {
            // Capability и aggregate boundary отказывают до RLS, валидации, хуков и SQL.
            if (scope != null) {
                scope.denied(denied.kind(), denied.getMessage());
            }
            throw denied;
        } catch (AccessDeniedException accessDenied) {
            // RLS/security-отказ — тоже deny: пользователю отказано в доступе, операция
            // не сломалась. Отдельная ветка нужна, потому что это исключение не проверяет
            // policy типа, а проверяет право на строку.
            if (scope != null) {
                scope.denied(WriteTelemetry.DenialKind.ACCESS, accessDenied.getMessage());
            }
            throw accessDenied;
        } catch (RuntimeException | Error error) {
            // Ошибка исполнения: валидация, нумерация, lifecycle, события, SQL. Считать это
            // deny нельзя — иначе настоящий отказ policy растворяется среди сбоев.
            if (scope != null) {
                scope.failed(error);
            }
            throw error;
        } finally {
            WRITE_TELEMETRY_DEPTH.set(WRITE_TELEMETRY_DEPTH.get() - 1);
            if (scope != null) {
                scope.close();
            }
        }
    }

    /**
     * C4.8: коммит выполняет Spring уже после возврата метода, поэтому успех pipeline не
     * является успехом записи. Исход транзакции сообщается отдельно — от synchronization, —
     * иначе откат отмечался бы как {@code SUCCESS}. Вне транзакции synchronization сообщить
     * нечего: там доступна только pipeline-фаза, и это соответствует контракту seam'а.
     */
    private void registerTransactionOutcome(WriteTelemetry.WriteScope scope) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    scope.committed();
                } else {
                    scope.rolledBack(rollbackReason(status));
                }
            }
        });
    }

    private static String rollbackReason(int status) {
        return switch (status) {
            case TransactionSynchronization.STATUS_ROLLED_BACK -> "STATUS_ROLLED_BACK";
            case TransactionSynchronization.STATUS_UNKNOWN -> "STATUS_UNKNOWN";
            default -> "STATUS_" + status;
        };
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
                // в INSERT через семантику EntityManager.merge(). Отказ — доступ-образный:
                // запись либо скрыта политикой, либо её нет.
                throw new CanonicalWriteDeniedException(WriteTelemetry.DenialKind.ACCESS,
                    "Нельзя обновить " + type.getSimpleName()
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
     * C4.6 (ADR-0007 §5): агрегат с owned-секциями нельзя сохранить прямым {@code create}
     * или {@code update}. Такой intent не несёт графа секций, поэтому сохранил бы только
     * шапку, а строки оставил бы прежними.
     *
     * <p>Aggregate boundary — {@code MetadataDrivenAggregateSaveService}: он заменяет секции
     * и затем вызывает {@link #save}. Отказ явный и происходит до RLS, до
     * валидации, хуков и событий — то есть до пользовательского кода.</p>
     */
    private void requireIntentCoversSections(Class<?> type, DataOperation operation) {
        if (sectionMetadataRegistry == null) {
            return;
        }
        List<TableSectionMetadataInfo> sections = sectionMetadataRegistry.forOwner(type);
        if (sections.isEmpty()) {
            return;
        }
        String keys = sections.stream()
            .map(TableSectionMetadataInfo::getKey)
            .sorted()
            .collect(Collectors.joining(", "));
        throw new CanonicalWriteDeniedException(WriteTelemetry.DenialKind.AGGREGATE_BOUNDARY,
            type.getSimpleName()
                + " — агрегат с owned-секциями (" + keys + "): прямой " + operation
                + " сохранил бы только шапку. Сохраняйте агрегат через aggregate boundary"
                + " (MetadataDrivenAggregateSaveService), который заменяет секции и вызывает save().");
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
            throw new CanonicalWriteDeniedException(WriteTelemetry.DenialKind.CAPABILITY,
                type.getSimpleName()
                    + " — строка owned-секции и не имеет автономного " + operation
                    + "-handle. Секция изменяется только aggregate boundary владельца ("
                    + descriptor.reason() + ").");
        }
        throw new CanonicalWriteDeniedException(WriteTelemetry.DenialKind.CAPABILITY,
            type.getSimpleName() + " не имеет canonical "
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
