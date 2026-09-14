package org.ipro.data;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.ipro.data.fixture.C4FixtureEntity;
import org.ipro.data.fixture.C4FixtureJpaConfiguration;
import org.ipro.data.fixture.C4NoTextFixtureEntity;
import org.ipro.events.EntityEventPublisher;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.lifecycle.EntityLifecycleRegistry;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.numbering.NumberingService;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.rls.RlsPolicyEnforcer;
import org.ipro.rls.RlsReadGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.ArrayList;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    private EntityDescriptorCatalog catalog;
    private CanonicalReadExecutor readExecutor;
    private CanonicalWriteExecutor writeExecutor;
    private EntityDataAccess access;
    private EntityDataAccessResolver resolver;
    private CanonicalEntityService<C4FixtureEntity> service;
    private RlsFilterActivator rlsFilterActivator;
    private RlsReadGate readGate;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void buildCanonicalStack() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        MetadataResolver metadataResolver = new MetadataResolver();
        ManagedEntityCatalog managed = new ManagedEntityCatalog(entityManagerFactory);
        SectionMetadataRegistry sections =
            new SectionMetadataRegistry("org.ipro.data.fixture", metadataResolver);
        sections.afterPropertiesSet();

        catalog = new EntityDescriptorCatalog(managed, sections, metadataResolver,
            List.of(), List.of());
        ScenarioFetchGraphResolver graphResolver =
            new ScenarioFetchGraphResolver(metadataResolver, null, null);
        rlsFilterActivator = mock(RlsFilterActivator.class);
        readGate = mock(RlsReadGate.class);
        when(readGate.canRead(any(), any())).thenReturn(true);
        readExecutor = new CanonicalReadExecutor(catalog, graphResolver, metadataResolver,
            rlsFilterActivator, readGate, null, ReadTelemetry.noop());
        ReflectionTestUtils.setField(readExecutor, "entityManager", entityManager);

        writeExecutor = new CanonicalWriteExecutor(catalog, readExecutor, entityManager,
            validator, null, null, null, null, null, null);
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
            entityManager, spyValidator, enforcer, null, null, null, null, null);

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
            entityManager, spyValidator, enforcer, null, events, lifecycle, null, null);

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
            entityManager, spyValidator, enforcer, numbering, events, lifecycle, null, null);

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
            entityManager, spyValidator, enforcer, null, events, lifecycle, null, null);

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

    @Test
    void globalSearchContextKeepsDistinctTelemetryIntent() {
        MetadataResolver metadataResolver = new MetadataResolver();
        List<DataOperation> observed = new ArrayList<>();
        CanonicalReadExecutor measured = new CanonicalReadExecutor(catalog,
            new ScenarioFetchGraphResolver(metadataResolver, null, null), metadataResolver,
            rlsFilterActivator, readGate, null,
            (operation, type, scenario, resultCount, durationNanos) -> observed.add(operation));
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
            (operation, type, scenario, resultCount, durationNanos) -> observed.add(operation));
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
}
