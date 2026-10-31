package org.ipro.data;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import jakarta.transaction.Transactional;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.ipro.crud.AggregateOperationAuthorizer;
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
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsPolicyEnforcer;
import org.ipro.rls.c5.C5Operation;
import org.ipro.rls.c5.C5PermissionConfigurationException;
import org.ipro.rls.c5.C5PermissionDecisionService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Единый canonical write pipeline (C4, ADR-0007 §5):
 *
 * <pre>
 * write intent
 * -&gt; capability типа (fail-closed)
 * -&gt; C5-решение об операции (fail-closed; ADR-0017; для именованной операции — именованное
 *    определение владельца с операцией EXECUTE, ADR-0019)
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
 * boundary), решением C5 ({@code C5AccessDeniedException}) и security-исключением
 * ({@link AccessDeniedException}); всё остальное — ошибка
 * исполнения. Телеметрия записи двухфазная: {@code pipelineCompleted} фиксируется после
 * flush внутри операции, а {@code committed}/{@code rolledBack} — по исходу транзакции,
 * потому что Spring коммитит уже после возврата метода.</p>
 *
 * <p><b>C5 — единственный владелец решения об операции (ADR-0017).</b> Пара
 * (тип, операция) проверяется после capability и <b>до</b> RLS, загрузки исходной строки,
 * нумерации, валидации, lifecycle-хуков и событий: запрещённый вызов не получает номер, не
 * меняет состояние и не вызывает пользовательский код. Отсутствующий провайдер решения —
 * ошибка конфигурации, а не allow; то же правило действует в canonical read.</p>
 */
public class CanonicalWriteExecutor implements AggregateOperationAuthorizer {

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
    private final List<CanonicalDeleteParticipant> deleteParticipants;
    private final RlsCurrentUser currentUser;
    private final C5PermissionDecisionService c5PermissionDecisionService;
    private final CanonicalWritePropertyPolicy propertyPolicy;
    /** Обязательная проверка owner-контракта; не Spring-бин и не отключается конфигурацией. */
    private final CatalogOwnerGuard catalogOwnerGuard;

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
            referenceCheckService, sectionMetadataRegistry, null, List.of());
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
        this(catalog, readExecutor, entityManager, validator, rlsPolicyEnforcer,
            numberingService, eventPublisher, lifecycleRegistry, ownedSectionService,
            referenceCheckService, sectionMetadataRegistry, writeTelemetry, List.of());
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
                                  WriteTelemetry writeTelemetry,
                                  List<CanonicalDeleteParticipant> deleteParticipants) {
        this(catalog, readExecutor, entityManager, validator, rlsPolicyEnforcer, numberingService,
            eventPublisher, lifecycleRegistry, ownedSectionService, referenceCheckService,
            sectionMetadataRegistry, writeTelemetry, deleteParticipants, null, null);
    }

    /**
     * Полный контур: к прежним policy owners добавлены субъект и единое решение C5 об
     * операции (ADR-0017). Отсутствие решения не означает allow — вызов write-intent'а
     * завершится ошибкой конфигурации, как и в canonical read без провайдера.
     */
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
                                  WriteTelemetry writeTelemetry,
                                  List<CanonicalDeleteParticipant> deleteParticipants,
                                  RlsCurrentUser currentUser,
                                  C5PermissionDecisionService c5PermissionDecisionService) {
        this(catalog, readExecutor, entityManager, validator, rlsPolicyEnforcer, numberingService,
            eventPublisher, lifecycleRegistry, ownedSectionService, referenceCheckService,
            sectionMetadataRegistry, writeTelemetry, deleteParticipants, currentUser,
            c5PermissionDecisionService, null);
    }

    /**
     * Полный контур с владельцем свойства, которое generic-запись не переносит (ADR-0019 §2 по
     * форме, ADR-0017 §12 по словарю операций): preflight получает предсостояние и цель и
     * спрашивает решение владельца до нумерации, валидации, хуков, событий и SQL-мутации.
     * {@code null} означает, что у типа таких свойств нет, — а не ослабление границ типа.
     */
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
                                  WriteTelemetry writeTelemetry,
                                  List<CanonicalDeleteParticipant> deleteParticipants,
                                  RlsCurrentUser currentUser,
                                  C5PermissionDecisionService c5PermissionDecisionService,
                                  CanonicalWritePropertyPolicy propertyPolicy) {
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
        this.deleteParticipants = List.copyOf(Objects.requireNonNull(deleteParticipants,
            "deleteParticipants must not be null"));
        // Субъект и решение C5 опциональны только как конфигурация slice-контекста: их
        // отсутствие не даёт права на запись, а приводит к отказу с явной причиной.
        this.currentUser = currentUser;
        this.c5PermissionDecisionService = c5PermissionDecisionService;
        // Политика свойства — тоже опциональный владелец: без неё у типов нет свойств, которые
        // generic-запись не переносит, и ни один канал не становится шире.
        this.propertyPolicy = propertyPolicy;
        // Owner-guard строится здесь, а не берётся необязательным бином: отсутствие
        // пользовательской конфигурации не должно отключать контракт владельца.
        this.catalogOwnerGuard = new CatalogOwnerGuard(entityManager);
    }

    /**
     * Preflight операции без записи: capability плюс единое решение C5 (ADR-0017).
     *
     * <p>Нужен вызывающим, которые проводят свою security-границу раньше executor'а — в
     * частности aggregate save, публикующий aggregate-события и before-хуки до корневого
     * save. Метод не выполняет ни SQL мутации, ни нумерации, ни валидации.</p>
     */
    @Override
    public void requireOperation(Class<?> type, DataOperation operation) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
        requireCapability(type, operation);
        requireOperationPermission(type, operation);
    }

    /** Descriptor типа — для диагностики вызывающих. */
    public EntityDescriptor descriptorOf(Class<?> type) {
        return catalog.descriptorOf(type);
    }

    @Transactional
    public <T extends IdentifiableEntity<Long>> T create(Class<T> type, T entity) {
        return instrumented(DataOperation.CREATE, type, () -> {
            requireIntentCoversSections(type, DataOperation.CREATE);
            return persist(type, entity, DataOperation.CREATE);
        });
    }

    @Transactional
    public <T extends IdentifiableEntity<Long>> T update(Class<T> type, T entity) {
        return instrumented(DataOperation.UPDATE, type, () -> {
            requireIntentCoversSections(type, DataOperation.UPDATE);
            return persist(type, entity, DataOperation.UPDATE);
        });
    }

    /**
     * Именованная операция владельца на строке (ADR-0019): право выражено ключом
     * {@code ACTION:<permissionId>} с операцией {@code EXECUTE}, а владелец применяет мутацию
     * к загруженной строке — не присылает сущность для слияния.
     *
     * <p>Порядок решения тот же, что у прочих намерений (ADR-0017 §8), но право приходит из
     * именованного определения владельца, а не из capability типа: canonical write handle типа →
     * определение зарегистрировано и поддерживает {@code EXECUTE} (внутри решения, до чтения
     * грантов) → решение об операции <b>до</b> загрузки строки и до SQL → canonical read строки
     * (C5-чтение и RLS исходного состояния) → мутация владельцем → фиксация авторизации записи в
     * том же журнале, что у canonical write → validation, hooks и события.</p>
     *
     * <p>{@code UPDATE}-capability при этом <b>не</b> требуется: тип, объявивший только
     * {@code CREATE}, generic update не публикует — и не публикует его и здесь, потому что вход
     * принимает мутацию на строке, а не сущность для слияния (ADR-0019 §2/§3). Поэтому ни
     * {@link #update}, ни {@link #save} до этого входа не доходят.</p>
     *
     * <p>Исходное состояние для {@code beforeUpdate} берётся отдельным canonical read: мутация
     * идёт на управляемом экземпляре, иначе хук получил бы один и тот же объект дважды. Читается
     * строка тем же путём, что у остальных намерений: недоступная субъекту строка обязана быть
     * неотличима от отсутствующей, а RLS исходного состояния решается этим чтением, а не вторым
     * {@code requireUpdate} — невидимая строка до мутации не доходит.</p>
     */
    @Transactional
    public <T extends IdentifiableEntity<Long>> T executeNamed(Class<T> type, String permissionId,
                                                         Object id, Consumer<T> mutation) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(mutation, "mutation must not be null");
        return instrumented(DataOperation.UPDATE, type, () ->
            mutateNamed(type, permissionId, id, mutation));
    }

    /** Create или update по наличию id — единый intent UI-формы. */
    @Transactional
    public <T extends IdentifiableEntity<Long>> T save(Class<T> type, T entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        DataOperation operation = entity.getId() == null
            ? DataOperation.CREATE : DataOperation.UPDATE;
        return instrumented(operation, type, () -> persist(type, entity, operation));
    }

    @Transactional
    public <T extends IdentifiableEntity<Long>> void delete(Class<T> type, Object id) {
        instrumented(DataOperation.DELETE, type, () -> {
            requireOperation(type, DataOperation.DELETE);
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
            Class<?> persistenceType = entityType(entity);
            List<CanonicalDeleteParticipant> participants = deleteParticipants.stream()
                .filter(participant -> participant.supports(persistenceType))
                .toList();
            if (!participants.isEmpty()) {
                entity = lockAndRefreshDeleteRoot(type, id, entity);
                if (entity == null) return false;
                authorizeDelete(entity);
            }
            EntityEventPublisher.EventScope scope = openOperation(entity);
            try {
                publishDeleting(entity);
                if (ownedSectionService != null) {
                    ownedSectionService.deleteAllOwnedSections(entity);
                }
                for (CanonicalDeleteParticipant participant : participants) {
                    participant.beforeDelete(entity);
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

    /** Lock and refresh a recorder document before dependent rows are cleaned up. */
    @SuppressWarnings("unchecked")
    private <T extends IdentifiableEntity<Long>> T lockAndRefreshDeleteRoot(
            Class<T> type, Object id, T previouslyRead) {
        if (!(previouslyRead instanceof BaseEntity base)) {
            throw new IllegalStateException("Canonical recorder delete requires BaseEntity version support: "
                + type.getName());
        }
        Long expectedVersion = base.getVersion() == null ? 0L : base.getVersion();
        Map<String, Object> hints = Map.of("jakarta.persistence.lock.timeout", 1_000);
        T managed = entityManager.contains(previouslyRead)
            ? previouslyRead : entityManager.find(type, id, LockModeType.PESSIMISTIC_WRITE, hints);
        if (managed == null) {
            return null;
        }
        if (!(managed instanceof BaseEntity managedBase)) {
            throw new IllegalStateException("Canonical recorder delete requires BaseEntity version support: "
                + type.getName());
        }
        entityManager.refresh(managed, LockModeType.PESSIMISTIC_WRITE, hints);
        Long actualVersion = managedBase.getVersion() == null ? 0L : managedBase.getVersion();
        if (!expectedVersion.equals(actualVersion)) {
            throw new OptimisticLockException("RECORDER_DELETE_VERSION_CONFLICT: expected="
                + expectedVersion + ", actual=" + actualVersion + " for "
                + type.getName() + "#" + id);
        }
        return managed;
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

    private <T extends IdentifiableEntity<Long>> T persist(Class<T> type, T entity,
                                                    DataOperation operation) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(entity, "entity must not be null");

        // Capability — первая enforcement-граница (ADR-0007 §2): проверяется по заявленной
        // паре (type, operation), поэтому диагностика запрета не подменяется проверкой
        // состояния объекта. Сразу за ней — C5-решение об операции, до RLS, загрузки
        // исходной строки, нумерации, валидации, хуков и событий.
        requireOperation(type, operation);

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
        Long originalVersion = original.map(CanonicalWriteExecutor::versionBeforeSave).orElse(null);
        authorizeUpdate(entity);
        requirePropertyChangeAuthorized(type, original.orElse(null), entity);
        CatalogOwnerToken ownerToken = catalogOwnerGuard.preflight(type, entity, isNew);
        normalizeVersion(entity);
        assignNumbers(entity);
        validate(entity);
        EntityEventPublisher.EventScope scope = openOperation(entity);
        try {
            publishSaving(entity);
            publishUpdating(original, entity);
            if (entity instanceof org.ipro.crud.InternedEntity interned
                    && type.isAnnotationPresent(org.ipro.crud.InternedContract.class)) {
                org.ipro.crud.InterningContracts.identity(type, interned);
            }
            // Хуки могли изменить владельца: сравниваем с id, проверенным preflight'ом.
            catalogOwnerGuard.recheck(ownerToken, entity);
            T saved = isNew ? persistNew(entity) : entityManager.merge(entity);
            publishSaved(saved, originalVersion);
            return saved;
        } finally {
            close(scope);
        }
    }

    private <T extends IdentifiableEntity<Long>> T persistNew(T entity) {
        entityManager.persist(entity);
        return entity;
    }

    /**
     * Тело именованной операции: границы — до загрузки строки, запись — под авторизацией,
     * зафиксированной тем же способом, что у canonical write.
     */
    private <T extends IdentifiableEntity<Long>> T mutateNamed(Class<T> type, String permissionId,
                                                         Object id, Consumer<T> mutation) {
        requireNamedWriteHandle(type);
        requireNamedOperation(permissionId);
        requireIntentCoversSections(type, DataOperation.UPDATE);
        if (id == null) {
            throw new CanonicalWriteDeniedException(WriteTelemetry.DenialKind.ACCESS,
                "Именованная операция '" + permissionId + "' требует id строки "
                    + type.getSimpleName());
        }
        // Предсостояние и цель мутации — два разных экземпляра: первый снимается с управления и
        // остаётся исходным состоянием для beforeUpdate, второй — управляемый и мутируемый.
        T original = readNamedRow(type, id, permissionId);
        Long originalVersion = versionBeforeSave(original);
        entityManager.detach(original);
        T target = readNamedRow(type, id, permissionId);
        CatalogOwnerToken ownerToken = catalogOwnerGuard.preflight(type, target, false);
        mutation.accept(target);
        authorizeUpdate(target);
        requirePropertyChangeAuthorized(type, original, target);
        catalogOwnerGuard.recheck(ownerToken, target);
        normalizeVersion(target);
        validate(target);
        EntityEventPublisher.EventScope scope = openOperation(target);
        try {
            publishSaving(target);
            publishUpdating(Optional.of(original), target);
            catalogOwnerGuard.recheck(ownerToken, target);
            T saved = entityManager.merge(target);
            publishSaved(saved, originalVersion);
            return saved;
        } finally {
            close(scope);
        }
    }

    /** Строка под именованную операцию: тот же canonical read, что у остальных намерений. */
    private <T extends IdentifiableEntity<Long>> T readNamedRow(Class<T> type, Object id,
                                                          String permissionId) {
        return readExecutor.readDetail(DetailRead.of(type, id)).orElseThrow(() ->
            new CanonicalWriteDeniedException(WriteTelemetry.DenialKind.ACCESS,
                "Нельзя выполнить операцию '" + permissionId + "' над " + type.getSimpleName()
                    + " id=" + id + ": запись отсутствует или недоступна"));
    }

    /**
     * Именованная операция — всё равно запись: тип обязан иметь canonical write handle, иначе она
     * создала бы запись там, где владелец её не публикует (owned row, internal store без моста,
     * тип с намеренно запрещёнными generic-записями). Конкретная операция здесь не проверяется: у
     * типа с одним {@code CREATE} права на generic update нет by design — ровно поэтому
     * именованная операция и существует.
     */
    private void requireNamedWriteHandle(Class<?> type) {
        EntityDescriptor descriptor = catalog.descriptorOf(type);
        if (!descriptor.capabilities().writes().isEmpty()) {
            return;
        }
        if (descriptor.exposure() == EntityExposure.OWNED_ROW) {
            throw new CanonicalWriteDeniedException(WriteTelemetry.DenialKind.CAPABILITY,
                type.getSimpleName() + " — строка owned-секции и не имеет автономного"
                    + " handle. Секция изменяется только aggregate boundary владельца ("
                    + descriptor.reason() + ").");
        }
        throw new CanonicalWriteDeniedException(WriteTelemetry.DenialKind.CAPABILITY,
            type.getSimpleName() + " не публикует ни одного canonical write-намерения ("
                + descriptor.exposure() + " («" + descriptor.reason() + "», policy: "
                + descriptor.capabilities().reason() + ")): именованная операция владельца —"
                + " тоже запись, и write-handle типу она не выдаёт.");
    }

    /**
     * Решение об именованной операции: определение владельца обязано существовать и объявлять
     * {@code EXECUTE}. Проверка идёт до загрузки строки и до SQL; незарегистрированное
     * определение — ошибка конфигурации, а не allow (ADR-0017 §5).
     */
    private void requireNamedOperation(String permissionId) {
        if (c5PermissionDecisionService == null) {
            throw new C5PermissionConfigurationException("Canonical named operation requires a"
                + " C5PermissionDecisionService; no provider is configured, so '"
                + permissionId + "' cannot be authorized");
        }
        String username = currentUser == null ? null : currentUser.username();
        c5PermissionDecisionService.requireAction(username, permissionId);
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

    /**
     * C5-решение об операции: "может ли этот субъект выполнить CREATE/UPDATE/DELETE над этим
     * типом" (ADR-0017 §1, §3). Строки, построчные состояния и {@code ' * '} — не предмет этой
     * проверки: ими владеет RLS, и подменять одно другим запрещено.
     *
     * <p>Read-intent'ы через этот метод не проверяются: их решает canonical read. Целиком
     * отсутствующее решение — ошибка конфигурации, а не разрешение; неизвестное определение и
     * неподдержанная операция тоже отказ конфигурации (это свойство самого решения).</p>
     */
    private void requireOperationPermission(Class<?> type, DataOperation operation) {
        C5Operation c5Operation = switch (operation) {
            case CREATE -> C5Operation.CREATE;
            case UPDATE -> C5Operation.UPDATE;
            case DELETE -> C5Operation.DELETE;
            default -> null;
        };
        if (c5Operation == null) {
            return;
        }
        if (c5PermissionDecisionService == null) {
            throw new C5PermissionConfigurationException("Canonical write requires a"
                + " C5PermissionDecisionService; no provider is configured, so " + operation
                + " cannot be authorized");
        }
        String username = currentUser == null ? null : currentUser.username();
        c5PermissionDecisionService.requireEntity(username, type, c5Operation);
    }

    /**
     * Ранний write-гейт: до валидации, хуков и before-событий, плюс финальная граница записи.
     *
     * <p>Авторизация операции фиксируется здесь, а не на flush, потому что canonical write —
     * единственное место, где решение об операции уже принято (capability плюс C5). Оба входа
     * идут через enforcer: для типа с RLS он решает строку, для типа без RLS — фиксирует
     * авторизацию canonical write в том же журнале. Без второго {@code flush} остался бы
     * единственным входом, который ничего не решает (ADR-0017 §9).</p>
     *
     * <p>Реестр границы принадлежит экземпляру, а не статике, поэтому enforcer — единственный
     * доступный здесь владелец того же реестра, что и у слушателя flush.</p>
     */
    private void authorizeUpdate(Object entity) {
        if (rlsPolicyEnforcer != null && entity != null) {
            rlsPolicyEnforcer.requireUpdate(entity);
            rlsPolicyEnforcer.recordCanonicalWrite(entity);
        }
    }

    private void authorizeDelete(Object entity) {
        if (rlsPolicyEnforcer != null && entity != null) {
            rlsPolicyEnforcer.requireDelete(entity);
            rlsPolicyEnforcer.recordCanonicalDelete(entity);
        }
    }

    /**
     * Свойство, которое generic-запись не переносит: изменение обязано быть решено именованной
     * операцией владельца. Спрашивается тем же preflight'ом, что capability и операция типа, — до
     * нумерации, валидации, хуков, событий и SQL-мутации. Отсутствие политики означает лишь, что у
     * типа таких свойств нет.
     */
    private void requirePropertyChangeAuthorized(Class<?> type, Object original, Object target) {
        if (propertyPolicy == null) {
            return;
        }
        propertyPolicy.requireAuthorizedChange(type, original, target);
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
        if (entity instanceof IdentifiableEntity<?> identifiable
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

    private <T extends IdentifiableEntity<Long>> Optional<T> originalForUpdate(T entity) {
        if (entity.getId() == null) {
            return Optional.empty();
        }
        @SuppressWarnings("unchecked")
        Class<T> type = (Class<T>) entityType(entity);
        return readExecutor.readDetail(DetailRead.of(type, entity.getId()));
    }

    private <T extends IdentifiableEntity<Long>> EntityEventPublisher.EventScope openOperation(T entity) {
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

    private static Long versionBeforeSave(Object entity) {
        return entity instanceof BaseEntity base ? (base.getVersion() == null ? 0L : base.getVersion()) : null;
    }

    private void publishSaved(Object entity, Long originalVersion) {
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
                invokeLifecycleOnSave(entity, context, originalVersion);
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
            ((IdentifiableEntity<?>) entity).getId(), EventSource.SYSTEM,
            "delete:" + type.getSimpleName()));
    }

    private EventContext lifecycleContext(Object entity, String operationPrefix) {
        Class<?> type = entityType(entity);
        Object id = entity instanceof IdentifiableEntity<?> identifiable ? identifiable.getId() : null;
        String operation = operationPrefix + type.getSimpleName();
        if (eventPublisher != null) {
            return eventPublisher.contextFor(type, id, EventSource.SYSTEM, operation);
        }
        return EventContext.forEntity(type, id, EventSource.SYSTEM, operation);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void invokeLifecycleBeforeSave(Object entity, EventContext context) {
        lifecycleRegistry.beforeSave((Class) entityType(entity), (IdentifiableEntity<?>) entity, context);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void invokeLifecycleBeforeUpdate(Object original, Object updated, EventContext context) {
        lifecycleRegistry.beforeUpdate((Class) entityType(updated), (IdentifiableEntity<?>) original,
            (IdentifiableEntity<?>) updated, context);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void invokeLifecycleOnSave(Object entity, EventContext context, Long originalVersion) {
        try (CanonicalSaveLifecycleScope savedScope = CanonicalSaveLifecycleScope.open(
                (IdentifiableEntity<?>) entity, context, originalVersion)) {
            lifecycleRegistry.onSave((Class) entityType(entity), (IdentifiableEntity<?>) entity, context);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void invokeLifecycleBeforeDelete(Object entity, EventContext context) {
        lifecycleRegistry.beforeDelete((Class) entityType(entity), (IdentifiableEntity<?>) entity,
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
