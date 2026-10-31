package org.ipro.data;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.FlushModeType;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.ipro.data.ownerfixture.OwnerContract;
import org.ipro.data.ownerfixture.OwnerContractor;
import org.ipro.data.ownerfixture.OwnerFixtureJpaConfiguration;
import org.ipro.crud.GenericOwnedSectionService;
import org.ipro.crud.MetadataDrivenAggregateSaveService;
import org.ipro.crud.ServiceLocator;
import org.ipro.events.EntityEventPublisher;
import org.ipro.events.EventSource;
import org.ipro.lifecycle.AggregateSaveContext;
import org.ipro.lifecycle.EntityLifecycle;
import org.ipro.lifecycle.EntityLifecycleRegistry;
import org.ipro.lifecycle.EntityUpdateContext;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.rls.RlsReadGate;
import org.ipro.rls.c5.C5PermissionDecisionService;
import org.ipro.rls.c5.C5PermissionEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * E3.R: обязательность и неизменяемость владельца на реальном persistence-контуре.
 *
 * <p>Проверяется именно доверенное сохранённое состояние: detached-копия, уже изменённый
 * managed-экземпляр, named mutation, lifecycle hook и явный owner-контекст команды не могут
 * обойти контракт. Сравнение — по id, поэтому другой экземпляр/proxy того же владельца
 * допустим.</p>
 */
@DataJpaTest(properties = "spring.sql.init.mode=never")
@ContextConfiguration(classes = OwnerFixtureJpaConfiguration.class)
class CatalogOwnerWriteIT {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private MetadataResolver metadataResolver;
    private SectionMetadataRegistry sections;
    private EntityDescriptorCatalog catalog;
    private CanonicalReadExecutor readExecutor;
    private CanonicalWriteExecutor writeExecutor;
    private Validator validator;

    @BeforeEach
    void buildCanonicalStack() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
        metadataResolver = new MetadataResolver();
        ManagedEntityCatalog managed = new ManagedEntityCatalog(entityManagerFactory);
        sections = new SectionMetadataRegistry(
            "org.ipro.data.ownerfixture", metadataResolver);
        sections.afterPropertiesSet();

        catalog = new EntityDescriptorCatalog(managed, sections, metadataResolver,
            List.of(), List.of());
        ScenarioFetchGraphResolver graphResolver =
            new ScenarioFetchGraphResolver(metadataResolver, null, null);
        RlsFilterActivator rlsFilterActivator = mock(RlsFilterActivator.class);
        RlsReadGate readGate = mock(RlsReadGate.class);
        when(readGate.canRead(any(), any())).thenReturn(true);
        readExecutor = new CanonicalReadExecutor(catalog, graphResolver, metadataResolver,
            rlsFilterActivator, readGate, null, ReadTelemetry.noop(), null,
            () -> "test-user", allowAllC5());
        ReflectionTestUtils.setField(readExecutor, "entityManager", entityManager);

        writeExecutor = new CanonicalWriteExecutor(catalog, readExecutor, entityManager,
            validator, null, null, null, null, null, null, null, null, List.of(),
            () -> "test-user", allowAllOperations());
    }

    @Test
    void ownerFactsArePublishedInTheDescriptor() {
        assertThat(catalog.descriptorOf(OwnerContract.class).owner())
            .isEqualTo(new org.ipro.metadata.CatalogOwnerDescriptor(
                "counterparty", OwnerContractor.class.getName()));
        assertThat(catalog.descriptorOf(OwnerContractor.class).owner()).isNull();
    }

    @Test
    void createRequiresOwner() {
        assertThatThrownBy(() -> writeExecutor.create(OwnerContract.class,
                new OwnerContract("Без владельца", null)))
            .isInstanceOf(CatalogOwnerViolationException.class)
            .satisfies(error -> assertThat(((CatalogOwnerViolationException) error).reason())
                .isEqualTo(CatalogOwnerViolationException.Reason.OWNER_REQUIRED));
    }

    @Test
    void createWithOwnerSucceeds() {
        OwnerContractor a = owner("A");
        OwnerContract created = writeExecutor.create(OwnerContract.class,
            new OwnerContract("C-1", a));

        entityManager.flush();
        assertThat(created.getId()).isNotNull();
        assertThat(created.getCounterparty().getId()).isEqualTo(a.getId());
    }

    @Test
    void detachedUpdateCannotChangeOwnerId() {
        OwnerContractor a = owner("A");
        OwnerContractor b = owner("B");
        OwnerContract created = writeExecutor.create(OwnerContract.class, new OwnerContract("C-2", a));
        entityManager.flush();
        entityManager.detach(created);

        OwnerContract detached = new OwnerContract("C-2", b);
        detached.setId(created.getId());

        assertThatThrownBy(() -> writeExecutor.update(OwnerContract.class, detached))
            .isInstanceOf(CatalogOwnerViolationException.class)
            .satisfies(error -> assertThat(((CatalogOwnerViolationException) error).reason())
                .isEqualTo(CatalogOwnerViolationException.Reason.OWNER_IMMUTABLE));
    }

    @Test
    void alreadyModifiedManagedChildCannotChangeOwnerId() {
        OwnerContractor a = owner("A");
        OwnerContractor b = owner("B");
        OwnerContract created = writeExecutor.create(OwnerContract.class, new OwnerContract("C-3", a));
        entityManager.flush();
        entityManager.clear();

        OwnerContract managed = entityManager.find(OwnerContract.class, created.getId());
        managed.setCounterparty(b);

        assertThatThrownBy(() -> writeExecutor.update(OwnerContract.class, managed))
            .isInstanceOf(CatalogOwnerViolationException.class)
            .satisfies(error -> assertThat(((CatalogOwnerViolationException) error).reason())
                .isEqualTo(CatalogOwnerViolationException.Reason.OWNER_IMMUTABLE));

        // COMMIT-запрос не выполняет auto-flush: сохранённое значение осталось A.
        assertThat(persistedOwnerId(created.getId())).isEqualTo(a.getId());
    }

    @Test
    void sameOwnerIdFromAnotherInstanceIsAccepted() {
        OwnerContractor a = owner("A");
        OwnerContract created = writeExecutor.create(OwnerContract.class, new OwnerContract("C-4", a));
        entityManager.flush();
        entityManager.clear();

        OwnerContract managed = entityManager.find(OwnerContract.class, created.getId());
        OwnerContractor sameIdOtherInstance = new OwnerContractor("A-копия");
        sameIdOtherInstance.setId(a.getId());
        managed.setCounterparty(sameIdOtherInstance);

        OwnerContract saved = writeExecutor.update(OwnerContract.class, managed);
        entityManager.flush();

        assertThat(saved.getCounterparty().getId()).isEqualTo(a.getId());
        assertThat(persistedOwnerId(created.getId())).isEqualTo(a.getId());
    }

    @Test
    void ownerProxyWithTheSameIdIsAccepted() {
        OwnerContractor a = owner("A");
        OwnerContract created = writeExecutor.create(OwnerContract.class, new OwnerContract("C-12", a));
        entityManager.flush();
        entityManager.clear();

        OwnerContract managed = entityManager.find(OwnerContract.class, created.getId());
        managed.setCounterparty(entityManager.getReference(OwnerContractor.class, a.getId()));

        OwnerContract saved = writeExecutor.update(OwnerContract.class, managed);
        entityManager.flush();

        assertThat(saved.getCounterparty().getId()).isEqualTo(a.getId());
        assertThat(persistedOwnerId(created.getId())).isEqualTo(a.getId());
    }

    @Test
    void namedMutationCannotTransferOwner() {
        OwnerContractor a = owner("A");
        OwnerContractor b = owner("B");
        OwnerContract created = writeExecutor.create(OwnerContract.class, new OwnerContract("C-5", a));
        entityManager.flush();

        assertThatThrownBy(() -> writeExecutor.executeNamed(OwnerContract.class,
                "owner.transfer", created.getId(), contract -> contract.setCounterparty(b)))
            .isInstanceOf(CatalogOwnerViolationException.class)
            .satisfies(error -> assertThat(((CatalogOwnerViolationException) error).reason())
                .isEqualTo(CatalogOwnerViolationException.Reason.OWNER_IMMUTABLE));
    }

    @Test
    void lifecycleHookCannotTransferOwner() {
        OwnerContractor a = owner("A");
        OwnerContractor b = owner("B");
        OwnerContract created = writeExecutor.create(OwnerContract.class, new OwnerContract("C-6", a));
        entityManager.flush();
        entityManager.clear();

        AtomicReference<OwnerContractor> replacement = new AtomicReference<>(b);
        EntityLifecycle<OwnerContract> hook = new EntityLifecycle<>() {
            @Override
            public Class<OwnerContract> entityType() {
                return OwnerContract.class;
            }

            @Override
            public void beforeUpdate(EntityUpdateContext<OwnerContract> context) {
                context.updated().setCounterparty(replacement.get());
            }
        };
        CanonicalWriteExecutor executorWithHook = new CanonicalWriteExecutor(
            catalog, readExecutor, entityManager, validator, null, null, null,
            new EntityLifecycleRegistry(List.of(hook)), null, null, null, null, List.of(),
            () -> "test-user", allowAllOperations());

        OwnerContract managed = entityManager.find(OwnerContract.class, created.getId());
        managed.setNumber("C-6-изменён");

        assertThatThrownBy(() -> executorWithHook.update(OwnerContract.class, managed))
            .isInstanceOf(CatalogOwnerViolationException.class)
            .hasMessageContaining("изменён после preflight");
    }

    @Test
    void aggregateCallbackCannotTransferOwnerBeforePersistence() {
        OwnerContractor a = owner("A");
        OwnerContractor b = owner("B");
        OwnerContract created = writeExecutor.create(OwnerContract.class, new OwnerContract("C-11", a));
        entityManager.flush();
        entityManager.clear();

        EntityEventPublisher events = mock(EntityEventPublisher.class);
        EntityLifecycle<OwnerContract> hook = new EntityLifecycle<>() {
            @Override
            public Class<OwnerContract> entityType() {
                return OwnerContract.class;
            }

            @Override
            public void beforeAggregateSave(AggregateSaveContext<OwnerContract> context) {
                context.aggregate().setCounterparty(b);
            }
        };
        ServiceLocator locator = mock(ServiceLocator.class);
        MetadataDrivenAggregateSaveService aggregates = new MetadataDrivenAggregateSaveService(
            locator, sections, mock(GenericOwnedSectionService.class), events,
            new EntityLifecycleRegistry(List.of(hook)), List.of(), null, entityManager);

        OwnerContract managed = entityManager.find(OwnerContract.class, created.getId());
        managed.setNumber("C-11-изменён");

        assertThatThrownBy(() -> aggregates.save(managed, List.of(), EventSource.UI))
            .isInstanceOf(CatalogOwnerViolationException.class)
            .hasMessageContaining("изменён после preflight");

        verify(events).publishAggregateSaving(any(), any(), any());
        verify(locator, never()).findService(any());
    }

    @Test
    void explicitOwnerContextMustMatchTheAssociation() {
        OwnerContractor a = owner("A");
        OwnerContractor b = owner("B");

        assertThatThrownBy(() -> CatalogOwnerContext.call(OwnerContractor.class.getName(),
                b.getId(), () -> writeExecutor.create(OwnerContract.class,
                    new OwnerContract("C-7", a))))
            .isInstanceOf(CatalogOwnerViolationException.class)
            .satisfies(error -> assertThat(((CatalogOwnerViolationException) error).reason())
                .isEqualTo(CatalogOwnerViolationException.Reason.CONTEXT_MISMATCH));

        OwnerContract created = CatalogOwnerContext.call(OwnerContractor.class.getName(),
            a.getId(), () -> writeExecutor.create(OwnerContract.class, new OwnerContract("C-8", a)));
        assertThat(created.getCounterparty().getId()).isEqualTo(a.getId());
    }

    @Test
    void deletingChildKeepsTheOwner() {
        OwnerContractor a = owner("A");
        OwnerContract created = writeExecutor.create(OwnerContract.class, new OwnerContract("C-9", a));
        entityManager.flush();

        writeExecutor.delete(OwnerContract.class, created.getId());
        entityManager.flush();

        assertThat(entityManager.find(OwnerContractor.class, a.getId())).isNotNull();
    }

    @Test
    void deletingOwnerWithChildrenIsRejectedByThePersistenceBoundary() {
        OwnerContractor a = owner("A");
        writeExecutor.create(OwnerContract.class, new OwnerContract("C-10", a));
        entityManager.flush();
        // Дочерняя запись не должна оставаться managed: проверяется сохранённая ссылка,
        // а не transient-состояние в текущем persistence context.
        entityManager.clear();

        assertThatThrownBy(() -> {
            writeExecutor.delete(OwnerContractor.class, a.getId());
            entityManager.flush();
        }).isInstanceOf(jakarta.persistence.PersistenceException.class);
    }

    private OwnerContractor owner(String name) {
        return writeExecutor.create(OwnerContractor.class, new OwnerContractor(name));
    }

    private Long persistedOwnerId(Long contractId) {
        List<?> rows = entityManager.createQuery(
                "select c.counterparty.id from OwnerContract c where c.id = :id", Object.class)
            .setFlushMode(FlushModeType.COMMIT)
            .setParameter("id", contractId)
            .getResultList();
        assertThat(rows).hasSize(1);
        return (Long) rows.getFirst();
    }

    private static C5PermissionDecisionService allowAllOperations() {
        return mock(C5PermissionDecisionService.class);
    }

    private C5PermissionEvaluator allowAllC5() {
        C5PermissionEvaluator evaluator = mock(C5PermissionEvaluator.class);
        when(evaluator.isEntityReadPermitted(any(), any())).thenReturn(true);
        return evaluator;
    }
}
