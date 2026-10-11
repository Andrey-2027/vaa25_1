package org.ipro.data;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.ipro.data.fixture.C4FixtureEntity;
import org.ipro.data.fixture.C4FixtureJpaConfiguration;
import org.ipro.data.fixture.C4NoTextFixtureEntity;
import org.ipro.data.fixture.C4VersionedFixtureEntity;
import org.ipro.data.ApiDetailRead;
import org.ipro.data.ApiPageRead;
import org.ipro.crud.ValidationException;
import org.ipro.events.EntityEventPublisher;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.lifecycle.EntityLifecycleRegistry;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.TableSectionMetadataInfo;
import org.ipro.numbering.NumberingService;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsAccessDeniedException;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.rls.RlsPolicyEnforcer;
import org.ipro.rls.RlsReadGate;
import org.ipro.rls.c5.C5AccessDeniedException;
import org.ipro.rls.c5.C5Operation;
import org.ipro.rls.c5.C5OperationPermissionRegistry;
import org.ipro.rls.c5.C5PermissionConfigurationException;
import org.ipro.rls.c5.C5PermissionDecisionService;
import org.ipro.rls.c5.C5PermissionDefinition;
import org.ipro.rls.c5.C5PermissionEvaluator;
import org.ipro.rls.c5.C5PermissionKeys;
import org.ipro.rls.c5.C5ReadDecisionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C4.3, DoD: стандартная metadata-driven entity проходит list/detail/create/update/delete
 * <b>без Spring Data repository, application service, {@code serviceClass} и bean-name
 * convention</b> (ADR-0007 §1, §5).
 *
 * <p>Persistence unit изолирован: в нём только test-only fixture entities, без сущностей
 * приложения, поэтому успех не объясняется чужой инфраструктурой. Canonical-стек собран
 * вручную из реальных platform-компонентов; RLS-граница замокана, потому что её предмет —
 * порядок и fail-fast поведение executor'а, а сама policy проверяется отдельными RLS-тестами.</p>
 */
@DataJpaTest(properties = "spring.sql.init.mode=never")
@ContextConfiguration(classes = C4FixtureJpaConfiguration.class)
@Import({})
class CanonicalWritePathIT {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private MetadataResolver metadataResolver;
    private ManagedEntityCatalog managed;
    private SectionMetadataRegistry sections;
    private EntityDescriptorCatalog catalog;
    private CanonicalReadExecutor readExecutor;
    private CanonicalWriteExecutor writeExecutor;
    private EntityDataAccess access;
    private EntityDataAccessResolver resolver;
    private CanonicalEntityService<C4FixtureEntity> service;
    private RlsFilterActivator rlsFilterActivator;
    private RlsReadGate readGate;
    private Validator validator;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void buildCanonicalStack() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
        metadataResolver = new MetadataResolver();
        managed = new ManagedEntityCatalog(entityManagerFactory);
        sections = new SectionMetadataRegistry("org.ipro.data.fixture", metadataResolver);
        sections.afterPropertiesSet();

        catalog = new EntityDescriptorCatalog(managed, sections, metadataResolver,
            List.of(), List.of());
        ScenarioFetchGraphResolver graphResolver =
            new ScenarioFetchGraphResolver(metadataResolver, null, null);
        rlsFilterActivator = mock(RlsFilterActivator.class);
        readGate = mock(RlsReadGate.class);
        when(readGate.canRead(any(), any())).thenReturn(true);
        readExecutor = new CanonicalReadExecutor(catalog, graphResolver, metadataResolver,
            rlsFilterActivator, readGate, null, ReadTelemetry.noop(), null,
            () -> "test-user", allowAllC5());
        ReflectionTestUtils.setField(readExecutor, "entityManager", entityManager);

        writeExecutor = new CanonicalWriteExecutor(catalog, readExecutor, entityManager,
            validator, null, null, null, null, null, null, null, null, List.of(),
            () -> "test-user", allowAllOperations());
        access = new CanonicalEntityDataAccess(catalog, readExecutor, writeExecutor, null);
        resolver = new EntityDataAccessResolver(catalog, access, readExecutor, List.of());
        service = new CanonicalEntityService<>(C4FixtureEntity.class, readExecutor, access);
    }

    @Test
    void fixtureHasNoRepositoryServiceServiceClassOrBeanName() {
        // Никакого Spring Data repository для типа не существует: persistence идёт через
        // EntityManager, а не через Spring-бин.
        assertThat(entityManagerFactory.getMetamodel().getEntities())
            .anySatisfy(entity -> assertThat(entity.getJavaType())
                .isEqualTo(C4FixtureEntity.class));

        EntityDescriptor descriptor = catalog.descriptorOf(C4FixtureEntity.class);
        assertThat(descriptor.exposure()).isEqualTo(EntityExposure.STANDARD_ROOT);
        assertThat(descriptor.metadataDriven()).isTrue();
        assertThat(descriptor.capabilities().writes())
            .containsExactlyInAnyOrder(DataOperation.CREATE, DataOperation.UPDATE,
                DataOperation.DELETE);
    }

    @Test
    void fullCrudWorksWithoutRepositoryOrService() {
        C4FixtureEntity created = service.create(new C4FixtureEntity("A-1", "Первый"));

        assertThat(created.getId()).isNotNull();
        Optional<C4FixtureEntity> detail = service.findById(created.getId());
        assertThat(detail).isPresent();
        assertThat(detail.get().getCode()).isEqualTo("A-1");

        List<C4FixtureEntity> all = service.findAll();
        assertThat(all).extracting(C4FixtureEntity::getCode).containsExactly("A-1");

        Page<C4FixtureEntity> page = service.findAll(PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(1);

        C4FixtureEntity toUpdate = detail.get();
        toUpdate.setName("Обновлённый");
        service.update(toUpdate);
        entityManager.flush();
        entityManager.clear();
        // Assertion на managed-объекте прошёл бы, даже если UPDATE не попал в БД:
        // после отсоединения перечитываем строку и проверяем именно persistence.
        assertThat(service.findById(created.getId()))
            .hasValueSatisfying(found -> assertThat(found.getName()).isEqualTo("Обновлённый"));

        service.delete(created.getId());
        assertThat(service.findById(created.getId())).isEmpty();
        assertThat(service.findAll()).isEmpty();
    }

    @Test
    void canonicalDeleteParticipantRunsWithTheLockedRootBeforeRootRemoval() {
        C4VersionedFixtureEntity created = new C4VersionedFixtureEntity("DEL-1");
        entityManager.persist(created);
        entityManager.flush();
        Long createdId = created.getId();
        entityManager.clear();
        List<IdentifiableEntity<Long>> observedRoots = new ArrayList<>();
        CanonicalDeleteParticipant participant = new CanonicalDeleteParticipant() {
            @Override
            public boolean supports(Class<?> rootType) {
                return rootType == C4VersionedFixtureEntity.class;
            }

            @Override
            public void beforeDelete(IdentifiableEntity<Long> lockedRoot) {
                observedRoots.add(lockedRoot);
                assertThat(entityManager.contains(lockedRoot)).isTrue();
            }
        };
        CanonicalWriteExecutor coordinated = new CanonicalWriteExecutor(catalog, readExecutor,
            entityManager, validator, null, null, null, null, null, null, null,
            WriteTelemetry.noop(), List.of(participant), () -> "test-user", allowAllOperations());

        coordinated.delete(C4VersionedFixtureEntity.class, createdId);

        assertThat(observedRoots).hasSize(1);
        assertThat(((C4VersionedFixtureEntity) observedRoots.getFirst()).getId()).isEqualTo(createdId);
        assertThat(entityManager.find(C4VersionedFixtureEntity.class, createdId)).isNull();
    }

    @Test
    void canonicalDeleteRejectsAStaleReadAfterLockAndRefresh() {
        C4VersionedFixtureEntity current = new C4VersionedFixtureEntity("DEL-VERSION-1");
        entityManager.persist(current);
        entityManager.flush();
        Long id = current.getId();
        current.setCode("DEL-VERSION-2");
        entityManager.flush();
        assertThat(current.getVersion()).isEqualTo(1L);

        C4VersionedFixtureEntity staleRead = new C4VersionedFixtureEntity("stale");
        staleRead.setId(id);
        staleRead.setVersion(0L);
        CanonicalReadExecutor staleReader = mock(CanonicalReadExecutor.class);
        doAnswer(invocation -> Optional.of(staleRead)).when(staleReader).readDetail(any());
        CanonicalDeleteParticipant participant = mock(CanonicalDeleteParticipant.class);
        when(participant.supports(C4VersionedFixtureEntity.class)).thenReturn(true);
        CanonicalWriteExecutor guarded = new CanonicalWriteExecutor(catalog, staleReader,
            entityManager, validator, null, null, null, null, null, null, null, null,
            List.of(participant), () -> "test-user", allowAllOperations());

        assertThatThrownBy(() -> guarded.delete(C4VersionedFixtureEntity.class, id))
            .isInstanceOf(jakarta.persistence.OptimisticLockException.class)
            .hasMessageContaining("RECORDER_DELETE_VERSION_CONFLICT");

        verify(participant, never()).beforeDelete(any());
        assertThat(entityManager.find(C4VersionedFixtureEntity.class, id)).isNotNull();
        assertThat(entityManager.find(C4VersionedFixtureEntity.class, id).getVersion()).isEqualTo(1L);
    }

    @Test
    void directCanonicalReadRejectsMissingEntityC5GrantBeforeRlsOrSql() {
        C5PermissionEvaluator denyC5 = mock(C5PermissionEvaluator.class);
        when(denyC5.isEntityReadPermitted("test-user", C4FixtureEntity.class)).thenReturn(false);
        CanonicalReadExecutor denied = new CanonicalReadExecutor(catalog,
            new ScenarioFetchGraphResolver(metadataResolver, null, null), metadataResolver,
            rlsFilterActivator, readGate, null, ReadTelemetry.noop(), null,
            () -> "test-user", denyC5);
        ReflectionTestUtils.setField(denied, "entityManager", entityManager);

        assertThatThrownBy(() -> denied.readAll(new ListRead<>(C4FixtureEntity.class,
            FetchScenario.LIST, null, PageRequest.of(0, 10), List.of())))
            .isInstanceOf(C5AccessDeniedException.class);
        org.mockito.Mockito.verifyNoInteractions(rlsFilterActivator);
    }

    @Test
    void apiReadsMapClassRlsDenialToEmptyListAndMissingDetail() {
        when(readGate.canRead(C4FixtureEntity.class, "test-user")).thenReturn(false);
        ApiPageRead<C4FixtureEntity> pageRequest = ApiPageRead.of(C4FixtureEntity.class, null,
            PageRequest.of(0, 10), List.of("id"));
        ApiDetailRead<C4FixtureEntity> detailRequest = ApiDetailRead.of(C4FixtureEntity.class,
            999L, List.of("id"));

        Page<C4FixtureEntity> page = readExecutor.readApiPage(pageRequest);
        Optional<C4FixtureEntity> detail = readExecutor.readApiDetail(detailRequest);

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
        assertThat(detail).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(rlsFilterActivator);
    }

    @Test
    void updateRequiresAnExistingRowAndCannotTurnIntoAnInsert() {
        C4FixtureEntity missing = new C4FixtureEntity("MISSING", "Не должна вставиться");
        missing.setId(987654321L);

        assertThatThrownBy(() -> access.update(C4FixtureEntity.class, missing))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("отсутствует или недоступна");

        entityManager.flush();
        entityManager.clear();
        assertThat(entityManager.find(C4FixtureEntity.class, missing.getId())).isNull();
    }

    @Test
    void updateAuthorizesStoredAndSubmittedStateBeforeValidation() {
        C4FixtureEntity saved = service.create(new C4FixtureEntity("ORIGINAL", "До"));
        entityManager.flush();
        entityManager.clear();

        C4FixtureEntity submitted = new C4FixtureEntity("TARGET", "После");
        submitted.setId(saved.getId());
        RlsPolicyEnforcer enforcer = mock(RlsPolicyEnforcer.class);
        List<String> authorizedCodes = new ArrayList<>();
        doAnswer(invocation -> {
            authorizedCodes.add(((C4FixtureEntity) invocation.getArgument(0)).getCode());
            return null;
        }).when(enforcer).requireUpdate(any());
        Validator spyValidator = spy(Validation.buildDefaultValidatorFactory().getValidator());
        CanonicalWriteExecutor guarded = new CanonicalWriteExecutor(catalog, readExecutor,
            entityManager, spyValidator, enforcer, null, null, null, null, null, null, null,
            List.of(), () -> "test-user", allowAllOperations());

        guarded.update(C4FixtureEntity.class, submitted);

        verify(enforcer, times(2)).requireUpdate(any());
        assertThat(authorizedCodes).containsExactly("ORIGINAL", "TARGET");

        var order = inOrder(enforcer, spyValidator);
        order.verify(enforcer, times(2)).requireUpdate(any());
        order.verify(spyValidator).validate(any());
    }

    @Test
    void denialOnSubmittedStateStopsAfterAuthorizingTheStoredState() {
        C4FixtureEntity saved = service.create(new C4FixtureEntity("ORIGINAL", "До"));
        entityManager.flush();
        entityManager.clear();

        C4FixtureEntity submitted = new C4FixtureEntity("FORBIDDEN", "После");
        submitted.setId(saved.getId());
        RlsPolicyEnforcer enforcer = mock(RlsPolicyEnforcer.class);
        doAnswer(invocation -> {
            C4FixtureEntity checked = invocation.getArgument(0);
            if ("FORBIDDEN".equals(checked.getCode())) {
                throw new IllegalStateException("Target RLS denied");
            }
            return null;
        }).when(enforcer).requireUpdate(any());
        Validator spyValidator = spy(Validation.buildDefaultValidatorFactory().getValidator());
        EntityLifecycleRegistry lifecycle = mock(EntityLifecycleRegistry.class);
        EntityEventPublisher events = mock(EntityEventPublisher.class);
        CanonicalWriteExecutor guarded = new CanonicalWriteExecutor(catalog, readExecutor,
            entityManager, spyValidator, enforcer, null, events, lifecycle, null, null, null,
            null, List.of(), () -> "test-user", allowAllOperations());

        assertThatThrownBy(() -> guarded.update(C4FixtureEntity.class, submitted))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Target RLS denied");

        verify(spyValidator, never()).validate(any());
        verify(lifecycle, never()).beforeSave(any(), any(), any());
        verify(lifecycle, never()).beforeUpdate(any(), any(), any(), any());
        verify(events, never()).publishSaving(any(), any());
    }

    /**
     * C4.3, DoD: RLS-отказ останавливает операцию до server validation, lifecycle hooks и
     * событий. Иначе запрещённый вызов раскрывал бы бизнес-валидацию или вызывал внешний
     * побочный эффект расширителя платформы.
     */
    /**
     * C4.3, DoD: успешная запись проходит весь заявленный порядок pipeline, а не только
     * {@code EntityManager + Bean Validation}. Прежний hand-built стек передавал null во все
     * policy owners, поэтому lifecycle/events/numbering не проверялись вообще.
     */
    @Test
    void successfulWriteRunsTheWholePipelineInOrder() {
        Validator spyValidator = spy(Validation.buildDefaultValidatorFactory().getValidator());
        EntityLifecycleRegistry lifecycle = mock(EntityLifecycleRegistry.class);
        EntityEventPublisher events = mock(EntityEventPublisher.class);
        NumberingService numbering = mock(NumberingService.class);
        RlsPolicyEnforcer enforcer = mock(RlsPolicyEnforcer.class);

        CanonicalWriteExecutor full = new CanonicalWriteExecutor(catalog, readExecutor,
            entityManager, spyValidator, enforcer, numbering, events, lifecycle, null, null, null,
            null, List.of(), () -> "test-user", allowAllOperations());

        C4FixtureEntity created = full.create(C4FixtureEntity.class,
            new C4FixtureEntity("B-1", "Полный"));

        assertThat(created.getId()).isNotNull();

        var order = inOrder(enforcer, numbering, spyValidator, events);
        order.verify(enforcer).requireUpdate(any());
        order.verify(numbering).assignAutoValues(any());
        order.verify(spyValidator).validate(any());
        order.verify(events).publishSaving(any(), any());
        order.verify(events).publishSaved(any(), any());
    }

    @Test
    void rlsDenialStopsBeforeValidationHooksAndEvents() {
        C4FixtureEntity saved = service.create(new C4FixtureEntity("RLS-1", "До отказа"));
        entityManager.flush();
        entityManager.clear();

        Validator spyValidator = spy(Validation.buildDefaultValidatorFactory().getValidator());
        EntityLifecycleRegistry lifecycle = mock(EntityLifecycleRegistry.class);
        EntityEventPublisher events = mock(EntityEventPublisher.class);
        RlsPolicyEnforcer enforcer = mock(RlsPolicyEnforcer.class);
        doThrow(new IllegalStateException("RLS denied")).when(enforcer).requireUpdate(any());

        CanonicalWriteExecutor guarded = new CanonicalWriteExecutor(catalog, readExecutor,
            entityManager, spyValidator, enforcer, null, events, lifecycle, null, null, null,
            null, List.of(), () -> "test-user", allowAllOperations());

        C4FixtureEntity existing = new C4FixtureEntity("RLS-1", "Попытка изменения");
        existing.setId(saved.getId());

        assertThatThrownBy(() -> guarded.update(C4FixtureEntity.class, existing))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("RLS denied");

        verify(spyValidator, never()).validate(any());
        verify(lifecycle, never()).beforeSave(any(), any(), any());
        verify(lifecycle, never()).beforeUpdate(any(), any(), any(), any());
        verify(events, never()).publishSaving(any(), any());
    }

    /**
     * C4.4, DoD: стандартный каталог ищется без repository query и service override.
     * Поля поиска выводятся из metadata, терм bounded, blank term — пустая выдача.
     */
    @Test
    void searchRunsWithoutRepositoryOrService() {
        service.create(new C4FixtureEntity("S-1", "Alpha"));
        service.create(new C4FixtureEntity("S-2", "Beta"));
        service.create(new C4FixtureEntity("S-3", "Alpha two"));

        Page<C4FixtureEntity> page = service.search("alpha", PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(C4FixtureEntity::getCode)
            .containsExactly("S-1", "S-3");

        // Blank term не является фильтром: выдача bounded и упорядочена по id.
        assertThat(service.search("   ")).extracting(C4FixtureEntity::getCode)
            .containsExactly("S-1", "S-2", "S-3");
        assertThat(service.search("S-2")).extracting(C4FixtureEntity::getCode)
            .containsExactly("S-2");
    }

    /**
     * C4.4: пользовательские {@code %} и {@code _} — литеральные символы, а не SQL-wildcard.
     */
    @Test
    void searchTreatsWildcardsLiterally() {
        service.create(new C4FixtureEntity("W-100%", "Процент"));
        service.create(new C4FixtureEntity("W-1000", "Тысяча"));

        assertThat(service.search("100%")).extracting(C4FixtureEntity::getCode)
            .containsExactly("W-100%");
        assertThat(service.search("100_")).isEmpty();
    }

    /** C4.4: порядок детерминирован — exact → prefix → substring, затем id. */
    @Test
    void searchOrderIsExactThenPrefixThenSubstring() {
        service.create(new C4FixtureEntity("ZZ-ORD-X-ZZ", "substring"));
        service.create(new C4FixtureEntity("ORD-X-2", "prefix"));
        service.create(new C4FixtureEntity("ORD-X", "exact"));

        assertThat(service.search("ord-x")).extracting(C4FixtureEntity::getCode)
            .containsExactly("ORD-X", "ORD-X-2", "ZZ-ORD-X-ZZ");
    }

    /** C4.4: paging bounded, totalElements считается отдельным query. */
    @Test
    void searchReportsPagedTotal() {
        for (int i = 0; i < 5; i++) {
            service.create(new C4FixtureEntity("PG-" + i, "page " + i));
        }

        Page<C4FixtureEntity> first = service.search("pg-", PageRequest.of(0, 2));
        assertThat(first.getTotalElements()).isEqualTo(5);
        assertThat(first.getContent()).extracting(C4FixtureEntity::getCode)
            .containsExactly("PG-0", "PG-1");

        Page<C4FixtureEntity> last = service.search("pg-", PageRequest.of(2, 2));
        assertThat(last.getContent()).extracting(C4FixtureEntity::getCode)
            .containsExactly("PG-4");
    }

    /** RLS-отказ на поиске возвращает пустую страницу, а не читает таблицу. */
    @Test
    void searchReturnsEmptyWhenRlsDeniesTheType() {
        service.create(new C4FixtureEntity("R-1", "Закрыто"));
        when(readGate.canRead(any(), any())).thenReturn(false);

        Page<C4FixtureEntity> page = service.search("закрыто", PageRequest.of(0, 10));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    void blankSearchIsBoundedWhenEntityHasNoStringSearchFields() {
        entityManager.persist(new C4NoTextFixtureEntity(true));
        entityManager.flush();
        entityManager.clear();

        Page<C4NoTextFixtureEntity> blank = readExecutor.readSearch(SearchRead.of(
            C4NoTextFixtureEntity.class, SearchContext.LIST, "   ", PageRequest.of(0, 10)));
        Page<C4NoTextFixtureEntity> nonBlank = readExecutor.readSearch(SearchRead.of(
            C4NoTextFixtureEntity.class, SearchContext.LIST, "active", PageRequest.of(0, 10)));

        assertThat(blank.getContent()).hasSize(1);
        assertThat(blank.getTotalElements()).isEqualTo(1);
        assertThat(nonBlank.getContent()).isEmpty();
    }

    /**
     * C4.8: blank-term и специальные символы не превращаются в unbounded read. Проверяется
     * измеримо: число SQL-запросов ограничено (content + count), а размер выдачи — page size,
     * а не размер таблицы.
     */
    @Test
    void blankAndSpecialCharacterSearchStayBounded() {
        for (int i = 0; i < 30; i++) {
            service.create(new C4FixtureEntity("B-" + i, i % 2 == 0 ? "alpha" : "beta"));
        }
        entityManager.flush();
        entityManager.clear();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        Page<C4FixtureEntity> blank = service.search("   ", PageRequest.of(0, 5));

        assertThat(blank.getContent()).hasSize(5);
        assertThat(blank.getTotalElements()).isEqualTo(30);
        assertThat(statistics.getQueryExecutionCount())
            .as("blank term — bounded page + count, а не вся таблица")
            .isEqualTo(2);

        statistics.clear();
        Page<C4FixtureEntity> literalPercent = service.search("%", PageRequest.of(0, 5));

        assertThat(literalPercent.getContent()).isEmpty();
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(2);
    }

    @Test
    void globalSearchContextKeepsDistinctTelemetryIntent() {
        MetadataResolver metadataResolver = new MetadataResolver();
        List<DataOperation> observed = new ArrayList<>();
        CanonicalReadExecutor measured = new CanonicalReadExecutor(catalog,
            new ScenarioFetchGraphResolver(metadataResolver, null, null), metadataResolver,
            rlsFilterActivator, readGate, null,
            (operation, type, scenario, outcome, resultCount, durationNanos) ->
                observed.add(operation), null, () -> "test-user", allowAllC5());
        ReflectionTestUtils.setField(measured, "entityManager", entityManager);

        measured.readSearch(new SearchRead<>(C4FixtureEntity.class, SearchContext.GLOBAL,
            "alpha", List.of(), PageRequest.of(0, 10), List.of()));

        assertThat(observed).containsExactly(DataOperation.GLOBAL_SEARCH);
    }

    @Test
    void globalSearchWindowUsesOneBoundedQueryWithoutCount() {
        service.create(new C4FixtureEntity("alpha", "точное"));
        service.create(new C4FixtureEntity("alpha-beta", "префикс"));
        service.create(new C4FixtureEntity("G-1", "contains alpha one"));
        service.create(new C4FixtureEntity("G-2", "contains alpha two"));
        entityManager.flush();
        entityManager.clear();

        List<DataOperation> observed = new ArrayList<>();
        CanonicalReadExecutor measured = new CanonicalReadExecutor(catalog,
            new ScenarioFetchGraphResolver(new MetadataResolver(), null, null),
            new MetadataResolver(), rlsFilterActivator, readGate, null,
            (operation, type, scenario, outcome, resultCount, durationNanos) ->
                observed.add(operation), null, () -> "test-user", allowAllC5());
        ReflectionTestUtils.setField(measured, "entityManager", entityManager);
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        List<C4FixtureEntity> results = measured.readSearchWindow(SearchRead.of(
            C4FixtureEntity.class, SearchContext.GLOBAL, "alpha"), 4, 1_500);

        assertThat(results).extracting(C4FixtureEntity::getCode)
            .containsExactly("alpha", "alpha-beta", "G-1", "G-2");
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(1);
        assertThat(observed).containsExactly(DataOperation.GLOBAL_SEARCH);
        verify(rlsFilterActivator).ensureRlsEnabled(entityManager);
    }

    @Test
    void beanValidationIsStillTheWriteContract() {
        assertThatThrownBy(() -> service.create(new C4FixtureEntity(null, "без кода")))
            .hasMessageContaining("code");
    }

    @Test
    void resolverHandsOutCanonicalHandleAndGenericService() {
        assertThat(resolver.resolve(C4FixtureEntity.class)).isSameAs(access);
        assertThat(resolver.findService(C4FixtureEntity.class))
            .hasValueSatisfying(handle -> assertThat(handle)
                .isInstanceOf(CanonicalEntityService.class));
        assertThat(resolver.resolutionReason(C4FixtureEntity.class))
            .contains("canonical generic path");
    }

    @Test
    void typeOutsideTheCatalogHasNoHandle() {
        assertThatThrownBy(() -> resolver.resolve(Object.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("UNCLASSIFIED");
        assertThat(resolver.find(Object.class)).isEmpty();
    }

    /**
     * C4.1 hardening: readLookup обязан активировать RLS-фильтры ровно так же, как
     * list/detail/sum. Стек собран без {@code RlsPolicyEnforcer} — это именно та
     * fallback-конфигурация, в которой canRead() ограничивается CHECK_ONLY-гейтом, и раньше
     * lookup оставался единственным чтением без FILTERABLE-predicates.
     */
    @Test
    void lookupActivatesRlsFiltersLikeEveryOtherRead() {
        readExecutor.readLookup(LookupRead.of(C4FixtureEntity.class, List.of("code"), "A", 10));

        verify(rlsFilterActivator).ensureRlsEnabled(entityManager);
    }

    @Test
    void readScenariosRemainScenarioBound() {
        assertThat(writeExecutor.allowedReads(C4FixtureEntity.class))
            .containsExactlyInAnyOrder(FetchScenario.LIST, FetchScenario.DETAIL,
                FetchScenario.LOOKUP);
        assertThat(writeExecutor.allowedWrites(C4FixtureEntity.class))
            .containsExactlyInAnyOrder(DataOperation.CREATE, DataOperation.UPDATE,
                DataOperation.DELETE);
    }

    // ------------------------------------------------------------------ C4.8 write-telemetry

    @Test
    void successfulWriteReportsPipelineThenCommitOutcome() {
        // Исход транзакции наблюдаем: успех обязан быть подтверждён коммитом, а не фактом
        // возврата метода — иначе откат на коммите записывался бы как успех.
        RecordingWriteTelemetry telemetry = new RecordingWriteTelemetry();
        CanonicalWriteExecutor executor = executorWith(telemetry);

        try {
            inNewTransaction(() -> executor.create(C4FixtureEntity.class,
                new C4FixtureEntity("T-COMMIT", "Запись")));

            assertThat(telemetry.events).containsExactly(
                "begin:CREATE", "pipelineCompleted:1", "close", "committed");
        } finally {
            removeCommittedRow("T-COMMIT");
        }
    }

    @Test
    void rolledBackTransactionIsNotReportedAsSuccess() {
        RecordingWriteTelemetry telemetry = new RecordingWriteTelemetry();
        CanonicalWriteExecutor executor = executorWith(telemetry);

        inNewTransaction(status -> {
            executor.create(C4FixtureEntity.class, new C4FixtureEntity("T-ROLLBACK", "Запись"));
            status.setRollbackOnly();
        });

        assertThat(telemetry.events).containsExactly(
            "begin:CREATE", "pipelineCompleted:1", "close", "rolledBack:STATUS_ROLLED_BACK");
    }

    @Test
    void capabilityDenialIsRecordedAsDeniedBeforePersistence() {
        // Тип без canonical write-handle: отказ обязан быть зафиксирован как deny с видом
        // policy, а не как ошибка исполнения — до RLS, валидации и SQL.
        RecordingWriteTelemetry telemetry = new RecordingWriteTelemetry();
        EntityDescriptorCatalog writeDenied = new EntityDescriptorCatalog(managed, sections,
            metadataResolver, List.of(), List.of(new EntityCapabilityOverride(
                C4FixtureEntity.class, Set.of(FetchScenario.LIST), Set.of(),
                "тест: canonical write не выдан")));
        CanonicalWriteExecutor executor = executorWith(writeDenied, entityManager, telemetry);

        assertThatThrownBy(() -> executor.create(C4FixtureEntity.class,
            new C4FixtureEntity("D-1", "запрещено")))
            .isInstanceOf(CanonicalWriteDeniedException.class);

        assertThat(telemetry.events).containsExactly(
            "begin:CREATE", "denied:CAPABILITY", "close");
    }

    @Test
    void aggregateBoundaryDenialIsRecordedAsDenied() {
        RecordingWriteTelemetry telemetry = new RecordingWriteTelemetry();
        SectionMetadataRegistry ownedSections = mock(SectionMetadataRegistry.class);
        TableSectionMetadataInfo section = mock(TableSectionMetadataInfo.class);
        when(section.getKey()).thenReturn("materials");
        when(ownedSections.forOwner(C4FixtureEntity.class)).thenReturn(List.of(section));
        CanonicalWriteExecutor executor = new CanonicalWriteExecutor(catalog, readExecutor,
            entityManager, validator, null, null, null, null, null, null, ownedSections, telemetry,
            List.of(), () -> "test-user", allowAllOperations());

        assertThatThrownBy(() -> executor.create(C4FixtureEntity.class,
            new C4FixtureEntity("A-1", "агрегат")))
            .isInstanceOf(CanonicalWriteDeniedException.class)
            .hasMessageContaining("aggregate boundary");

        assertThat(telemetry.events).containsExactly(
            "begin:CREATE", "denied:AGGREGATE_BOUNDARY", "close");
    }

    @Test
    void accessDenialIsRecordedAsDeniedNotAsError() {
        // RlsAccessDeniedException наследует AccessDeniedException: это отказ доступа, а не
        // сбой pipeline. До C4.8 такое исключение попадало в ошибки.
        RecordingWriteTelemetry telemetry = new RecordingWriteTelemetry();
        RlsPolicyEnforcer enforcer = mock(RlsPolicyEnforcer.class);
        doThrow(new RlsAccessDeniedException("нет права записи")).when(enforcer).requireUpdate(any());
        CanonicalWriteExecutor executor = new CanonicalWriteExecutor(catalog, readExecutor,
            entityManager, validator, enforcer, null, null, null, null, null, null, telemetry,
            List.of(), () -> "test-user", allowAllOperations());

        assertThatThrownBy(() -> executor.create(C4FixtureEntity.class,
            new C4FixtureEntity("R-1", "нет прав")))
            .isInstanceOf(RlsAccessDeniedException.class);

        assertThat(telemetry.events).containsExactly(
            "begin:CREATE", "denied:ACCESS", "close");
    }

    @Test
    void wrongIntentIsRecordedAsErrorNotAsDenial() {
        // create существующей строки — ошибка вызывающего, а не отказ policy: раньше любой
        // IllegalStateException попадал в deny и растворял настоящие отказы.
        RecordingWriteTelemetry telemetry = new RecordingWriteTelemetry();
        CanonicalWriteExecutor executor = executorWith(telemetry);
        C4FixtureEntity withId = new C4FixtureEntity("D-1", "уже существует");
        withId.setId(4242L);

        assertThatThrownBy(() -> executor.create(C4FixtureEntity.class, withId))
            .isInstanceOf(IllegalStateException.class)
            .isNotInstanceOf(CanonicalWriteDeniedException.class);

        assertThat(telemetry.events).containsExactly(
            "begin:CREATE", "failed:IllegalStateException", "close");
    }

    @Test
    void failingWriteIsRecordedAsFailedNotSuccess() {
        RecordingWriteTelemetry telemetry = new RecordingWriteTelemetry();
        CanonicalWriteExecutor executor = executorWith(telemetry);

        assertThatThrownBy(() -> executor.create(C4FixtureEntity.class,
            new C4FixtureEntity(null, "без кода")))
            .isInstanceOf(ValidationException.class);

        assertThat(telemetry.events).containsExactly(
            "begin:CREATE", "failed:ValidationException", "close");
    }

    @Test
    void databaseRejectionInsidePipelineIsRecordedAsFailedNotSuccess() {
        // Отказ БД на flush принадлежит этой операции: flush выполняется внутри pipeline,
        // поэтому отклонённая база не может быть записана как успех.
        RecordingWriteTelemetry telemetry = new RecordingWriteTelemetry();
        EntityManager rejecting = mock(EntityManager.class);
        doThrow(new PersistenceException("ограничение БД")).when(rejecting).flush();
        CanonicalWriteExecutor executor = executorWith(catalog, rejecting, telemetry);

        assertThatThrownBy(() -> executor.create(C4FixtureEntity.class,
            new C4FixtureEntity("B-1", "ограничение")))
            .isInstanceOf(PersistenceException.class);

        assertThat(telemetry.events).containsExactly(
            "begin:CREATE", "failed:PersistenceException", "close");
    }

    // ------------------------------------------------------------- C5.6: решение об операции

    /**
     * C5.6, DoD: отказ C5 останавливает запись до RLS, нумерации, server validation,
     * lifecycle-хуков и before-событий — то есть до пользовательского кода и до SQL.
     */
    @Test
    void c5DenialStopsBeforeRlsNumberingValidationHooksAndEvents() {
        Validator spyValidator = spy(Validation.buildDefaultValidatorFactory().getValidator());
        EntityLifecycleRegistry lifecycle = mock(EntityLifecycleRegistry.class);
        EntityEventPublisher events = mock(EntityEventPublisher.class);
        NumberingService numbering = mock(NumberingService.class);
        RlsPolicyEnforcer enforcer = mock(RlsPolicyEnforcer.class);
        C5PermissionDecisionService denyAll = mock(C5PermissionDecisionService.class);
        doThrow(new C5AccessDeniedException("Нет прав на операцию с объектом"))
            .when(denyAll).requireEntity(any(), any(), any());
        CanonicalWriteExecutor guarded = new CanonicalWriteExecutor(catalog, readExecutor,
            entityManager, spyValidator, enforcer, numbering, events, lifecycle, null, null, null,
            null, List.of(), () -> "test-user", denyAll);

        assertThatThrownBy(() -> guarded.create(C4FixtureEntity.class,
            new C4FixtureEntity("C5-DENY-1", "запрещено")))
            .isInstanceOf(C5AccessDeniedException.class);

        verify(numbering, never()).assignAutoValues(any());
        verify(spyValidator, never()).validate(any());
        verify(lifecycle, never()).beforeSave(any(), any(), any());
        verify(events, never()).publishSaving(any(), any());
        verify(enforcer, never()).requireUpdate(any());
        assertThat(entityManager.createQuery(
            "select count(e) from C4FixtureEntity e where e.code = :code")
            .setParameter("code", "C5-DENY-1").getSingleResult()).isEqualTo(0L);
    }

    /** Gate C5.6: тип закрыт без гранта на операцию и открыт, когда грант выдан. */
    @Test
    void writeIsClosedWithoutAnOperationGrantAndOpenWithOne() {
        AccessGrantRepository grants = mock(AccessGrantRepository.class);
        String key = C5PermissionKeys.entityOperations(C4FixtureEntity.class);
        when(grants.findBySubjectTypeAndSubjectKeyAndDimensionAndDimensionValueIdIsNull(
            AccessGrant.SubjectType.USER, "test-user", key)).thenReturn(List.of());
        CanonicalWriteExecutor executor = new CanonicalWriteExecutor(catalog, readExecutor,
            entityManager, validator, null, null, null, null, null, null, null, null, List.of(),
            () -> "test-user", operationsWithGrants(grants));

        assertThatThrownBy(() -> executor.create(C4FixtureEntity.class,
            new C4FixtureEntity("C5-CLOSED", "нет гранта")))
            .isInstanceOf(C5AccessDeniedException.class)
            .hasMessageContaining("CREATE");

        when(grants.findBySubjectTypeAndSubjectKeyAndDimensionAndDimensionValueIdIsNull(
            AccessGrant.SubjectType.USER, "test-user", key))
            .thenReturn(List.of(operationGrant(true, true)));
        C4FixtureEntity created = executor.create(C4FixtureEntity.class,
            new C4FixtureEntity("C5-OPEN", "грант есть"));

        assertThat(created.getId()).isNotNull();
        assertThat(entityManager.createQuery(
            "select count(e) from C4FixtureEntity e where e.code = :code")
            .setParameter("code", "C5-OPEN").getSingleResult()).isEqualTo(1L);

        AccessGrant deleteOnly = operationGrant(false, true);
        when(grants.findBySubjectTypeAndSubjectKeyAndDimensionAndDimensionValueIdIsNull(
            AccessGrant.SubjectType.USER, "test-user", key)).thenReturn(List.of(deleteOnly));
        assertThatThrownBy(() -> executor.create(C4FixtureEntity.class,
            new C4FixtureEntity("C5-DELETE-ONLY", "только удаление")))
            .isInstanceOf(C5AccessDeniedException.class);
    }

    /** ADR-0017 §3: wildcard-строка не авторизует операцию и C5 её вообще не запрашивает. */
    @Test
    void wildcardRowDoesNotAuthorizeCanonicalWrite() {
        AccessGrantRepository grants = mock(AccessGrantRepository.class);
        String key = C5PermissionKeys.entityOperations(C4FixtureEntity.class);
        AccessGrant wildcard = operationGrant(true, true);
        wildcard.setDimension("*");
        when(grants.findUserGrants(key, "test-user")).thenReturn(List.of(wildcard));
        when(grants.findBySubjectTypeAndSubjectKeyAndDimensionAndDimensionValueIdIsNull(
            any(), any(), any())).thenReturn(List.of());
        CanonicalWriteExecutor executor = new CanonicalWriteExecutor(catalog, readExecutor,
            entityManager, validator, null, null, null, null, null, null, null, null, List.of(),
            () -> "test-user", operationsWithGrants(grants));

        assertThatThrownBy(() -> executor.create(C4FixtureEntity.class,
            new C4FixtureEntity("C5-WILDCARD", "обходной путь")))
            .isInstanceOf(C5AccessDeniedException.class);
        verify(grants, never()).findUserGrants(any(), any());
    }

    /** Отсутствующий провайдер — ошибка конфигурации, а не allow (как и в canonical read). */
    @Test
    void missingDecisionProviderIsAConfigurationErrorNotAnAllow() {
        CanonicalWriteExecutor unconfigured = new CanonicalWriteExecutor(catalog, readExecutor,
            entityManager, validator, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> unconfigured.create(C4FixtureEntity.class,
            new C4FixtureEntity("C5-NO-PROVIDER", "не сконфигурировано")))
            .isInstanceOf(C5PermissionConfigurationException.class)
            .hasMessageContaining("no provider is configured");
        assertThat(entityManager.createQuery(
            "select count(e) from C4FixtureEntity e where e.code = :code")
            .setParameter("code", "C5-NO-PROVIDER").getSingleResult()).isEqualTo(0L);
    }

    /**
     * Шов владельца свойства (C5.8e): preflight получает и предсостояние, и цель записи.
     *
     * <p>Доказывается не «политика вызвана», а две вещи сразу: владельцу видно <b>что было</b> и
     * <b>что записывается</b> (иначе решение о «свойство тронуто?» принимать не по чему), и отказ
     * владельца останавливает запись там же, где останавливает отказ операции, — до валидации,
     * хуков, событий и SQL. Контроль невакуумности — в соседнем тесте: та же запись с разрешением
     * владельца доходит до строки.</p>
     */
    @Test
    void thePropertyPolicySeesThePreviousStateAndDenialStopsTheWrite() {
        C4FixtureEntity saved = service.create(new C4FixtureEntity("POLICY-1", "Исходное"));
        entityManager.flush();

        CanonicalWritePropertyPolicy policy = denyingPolicy();
        EntityLifecycleRegistry lifecycle = mock(EntityLifecycleRegistry.class);
        EntityEventPublisher events = mock(EntityEventPublisher.class);
        Validator spyValidator = spy(validator);
        CanonicalWriteExecutor guarded = executorWith(policy, spyValidator, events, lifecycle);

        C4FixtureEntity changed = new C4FixtureEntity("POLICY-1", "Изменённое");
        changed.setId(saved.getId());

        assertThatThrownBy(() -> guarded.update(C4FixtureEntity.class, changed))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("property denied");

        ArgumentCaptor<Object> original = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> target = ArgumentCaptor.forClass(Object.class);
        verify(policy).requireAuthorizedChange(eq(C4FixtureEntity.class), original.capture(),
            target.capture());
        assertThat(((C4FixtureEntity) original.getValue()).getName())
            .as("владельцу видно сохранённое состояние, а не только присланное")
            .isEqualTo("Исходное");
        assertThat(((C4FixtureEntity) target.getValue()).getName()).isEqualTo("Изменённое");
        verify(spyValidator, never()).validate(any());
        verify(lifecycle, never()).beforeSave(any(), any(), any());
        verify(lifecycle, never()).beforeUpdate(any(), any(), any(), any());
        verify(events, never()).publishSaving(any(), any());
    }

    /** Создания предсостояния не имеет: владельцу передаётся {@code null}, а не пустая строка. */
    @Test
    void creationAsksTheSamePolicyWithoutAPreviousState() {
        CanonicalWritePropertyPolicy policy = mock(CanonicalWritePropertyPolicy.class);
        CanonicalWriteExecutor guarded = executorWith(policy, validator, null, null);

        C4FixtureEntity created = guarded.create(C4FixtureEntity.class,
            new C4FixtureEntity("POLICY-2", "Созданное"));
        entityManager.flush();

        ArgumentCaptor<Object> original = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> target = ArgumentCaptor.forClass(Object.class);
        verify(policy).requireAuthorizedChange(eq(C4FixtureEntity.class), original.capture(),
            target.capture());
        assertThat(original.getValue()).as("предсостояния у создания нет").isNull();
        assertThat(((C4FixtureEntity) target.getValue()).getCode()).isEqualTo("POLICY-2");
        assertThat(created.getId()).isNotNull();

        // Контроль невакуумности: тот же путь с запретом владельца отказывает и строки не создаёт.
        CanonicalWriteExecutor blocked = executorWith(denyingPolicy(), validator, null, null);
        assertThatThrownBy(() -> blocked.create(C4FixtureEntity.class,
            new C4FixtureEntity("POLICY-3", "Отклонённое")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("property denied");
        assertThat(entityManager.createQuery(
                "select count(e) from C4FixtureEntity e where e.code = :code")
            .setParameter("code", "POLICY-3").getSingleResult())
            .as("отказ владельца не оставляет строки")
            .isEqualTo(0L);
    }

    /** Разрешённое владельцем изменение доходит до строки — отказ был про свойство, а не про канал. */
    @Test
    void anAllowedPropertyChangeStillWrites() {
        C4FixtureEntity saved = service.create(new C4FixtureEntity("POLICY-4", "Исходное"));
        entityManager.flush();
        entityManager.clear();

        CanonicalWritePropertyPolicy policy = mock(CanonicalWritePropertyPolicy.class);
        CanonicalWriteExecutor guarded = executorWith(policy, validator, null, null);

        C4FixtureEntity changed = new C4FixtureEntity("POLICY-4", "Разрешённое");
        changed.setId(saved.getId());
        guarded.update(C4FixtureEntity.class, changed);
        entityManager.flush();
        entityManager.clear();

        verify(policy).requireAuthorizedChange(eq(C4FixtureEntity.class), any(), any());
        assertThat(entityManager.find(C4FixtureEntity.class, saved.getId()).getName())
            .isEqualTo("Разрешённое");
    }

    /**
     * Именованная операция владельца — тот же канал записи, и шов спрашивается в ней тоже:
     * предсостояние снято с управления, цель — управляемый экземпляр после мутации владельца.
     */
    @Test
    void theNamedOperationChannelAsksTheSamePolicyWithTheDetachedPreviousState() {
        C4FixtureEntity saved = service.create(new C4FixtureEntity("POLICY-5", "Исходное"));
        entityManager.flush();
        entityManager.clear();

        CanonicalWritePropertyPolicy policy = denyingPolicy();
        EntityLifecycleRegistry lifecycle = mock(EntityLifecycleRegistry.class);
        EntityEventPublisher events = mock(EntityEventPublisher.class);
        Validator spyValidator = spy(validator);
        CanonicalWriteExecutor guarded = executorWith(policy, spyValidator, events, lifecycle);

        assertThatThrownBy(() -> guarded.executeNamed(C4FixtureEntity.class, NAMED_PERMISSION,
                saved.getId(), target -> target.setName("Новое")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("property denied");

        ArgumentCaptor<Object> original = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> target = ArgumentCaptor.forClass(Object.class);
        verify(policy).requireAuthorizedChange(eq(C4FixtureEntity.class), original.capture(),
            target.capture());
        assertThat(original.getValue())
            .as("предсостояние — отдельный экземпляр, иначе решение сравнивало бы значение с собой")
            .isNotSameAs(target.getValue());
        assertThat(((C4FixtureEntity) original.getValue()).getName()).isEqualTo("Исходное");
        assertThat(((C4FixtureEntity) target.getValue()).getName()).isEqualTo("Новое");
        verify(spyValidator, never()).validate(any());
        verify(lifecycle, never()).beforeUpdate(any(), any(), any(), any());
        verify(events, never()).publishSaving(any(), any());
    }

    /** Именованная операция фикстуры: определение регистрирует решающий сервис, здесь — allow. */
    private static final String NAMED_PERMISSION = "C4_FIXTURE:RENAME";

    /** Владелец свойства, который отказывает любой записи: предмет тестов — место вопроса, не ответ. */
    private static CanonicalWritePropertyPolicy denyingPolicy() {
        CanonicalWritePropertyPolicy policy = mock(CanonicalWritePropertyPolicy.class);
        doThrow(new IllegalStateException("property denied")).when(policy)
            .requireAuthorizedChange(any(), any(), any());
        return policy;
    }

    /** Полный контур с владельцем свойства; остальные швы те же, что у общего executor'а. */
    private CanonicalWriteExecutor executorWith(CanonicalWritePropertyPolicy propertyPolicy,
                                                Validator validationPort,
                                                EntityEventPublisher eventPublisher,
                                                EntityLifecycleRegistry lifecycleRegistry) {
        return new CanonicalWriteExecutor(catalog, readExecutor, entityManager, validationPort,
            null, null, eventPublisher, lifecycleRegistry, null, null, null, null, List.of(),
            () -> "test-user", allowAllOperations(), propertyPolicy);
    }

    private CanonicalWriteExecutor executorWith(WriteTelemetry telemetry) {
        return executorWith(catalog, entityManager, telemetry);
    }

    private CanonicalWriteExecutor executorWith(EntityDescriptorCatalog descriptors,
                                                EntityManager manager,
                                                WriteTelemetry telemetry) {
        return new CanonicalWriteExecutor(descriptors, readExecutor, manager, validator,
            null, null, null, null, null, null, null, telemetry, List.of(),
            () -> "test-user", allowAllOperations());
    }

    /** Явный allow-all double: тесты порядка проверяют pipeline, а не решение о правах. */
    private static C5PermissionDecisionService allowAllOperations() {
        return mock(C5PermissionDecisionService.class);
    }

    /** Настоящее решение C5 над подменённым grant store: одна строка — одно право. */
    private static C5PermissionDecisionService operationsWithGrants(AccessGrantRepository grants) {
        C5OperationPermissionRegistry registry = new C5OperationPermissionRegistry();
        registry.register(C5PermissionDefinition.entityOperations(C4FixtureEntity.class,
            Set.of(C5Operation.CREATE, C5Operation.UPDATE, C5Operation.DELETE)));
        return new C5PermissionDecisionService(grants, username -> List.of(), registry,
            new C5ReadDecisionService(grants, username -> List.of()));
    }

    private static AccessGrant operationGrant(boolean update, boolean delete) {
        AccessGrant grant = new AccessGrant();
        grant.setSubjectType(AccessGrant.SubjectType.USER);
        grant.setSubjectKey("test-user");
        grant.setDimension(C5PermissionKeys.entityOperations(C4FixtureEntity.class));
        grant.setDimensionValueId(null);
        grant.setCanUpdate(update);
        grant.setCanDelete(delete);
        return grant;
    }

    /** Транзакция, исход которой наблюдаем: REQUIRES_NEW коммитится или откатывается. */
    private void inNewTransaction(java.util.function.Consumer<TransactionStatus> body) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.execute(status -> {
            body.accept(status);
            return null;
        });
    }

    private void inNewTransaction(Runnable body) {
        inNewTransaction(status -> body.run());
    }

    /**
     * Коммит в отдельной транзакции переживает откат теста: строка убирается, чтобы не влиять
     * на остальные тесты этого контекста.
     */
    private void removeCommittedRow(String code) {
        inNewTransaction(status -> entityManager
            .createQuery("delete from C4FixtureEntity e where e.code = :code")
            .setParameter("code", code)
            .executeUpdate());
    }

    /** Минимальная реализация seam'а, фиксирующая порядок и исход, без payload. */
    private static final class RecordingWriteTelemetry implements WriteTelemetry {

        private final List<String> events = new ArrayList<>();

        @Override
        public WriteScope begin(DataOperation operation, Class<?> type) {
            events.add("begin:" + operation);
            return new WriteScope() {
                @Override
                public void denied(DenialKind kind, String reason) {
                    events.add("denied:" + kind);
                }

                @Override
                public void failed(Throwable error) {
                    events.add("failed:" + error.getClass().getSimpleName());
                }

                @Override
                public void pipelineCompleted(int affectedRows) {
                    events.add("pipelineCompleted:" + affectedRows);
                }

                @Override
                public void committed() {
                    events.add("committed");
                }

                @Override
                public void rolledBack(String reason) {
                    events.add("rolledBack:" + reason);
                }

                @Override
                public void close() {
                    events.add("close");
                }
            };
        }
    }

    private C5PermissionEvaluator allowAllC5() {
        C5PermissionEvaluator evaluator = mock(C5PermissionEvaluator.class);
        when(evaluator.isEntityReadPermitted(any(), any())).thenReturn(true);
        return evaluator;
    }
}
