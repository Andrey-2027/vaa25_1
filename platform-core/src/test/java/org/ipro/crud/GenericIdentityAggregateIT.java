package org.ipro.crud;

import jakarta.persistence.EntityManager;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.proxy.HibernateProxy;
import org.ipro.events.AggregateSection;
import org.ipro.events.EntityEventPublisher;
import org.ipro.events.EventSource;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.ig4fixture.AggregateFixtures.Movement;
import org.ipro.ig4fixture.AggregateFixtures.Root;
import org.ipro.ig4fixture.AggregateFixtures.Row;
import org.ipro.lifecycle.EntityLifecycleRegistry;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.TableSectionMetadataInfo;
import org.ipro.metadata.annotation.TableSectionMetadata;
import org.ipro.rls.RlsPolicyEnforcer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GenericIdentityAggregateIT {
    private SessionFactory factory;
    private ValidatorFactory validators;
    private EntityManager em;
    private TransactionTemplate transaction;
    private GenericOwnedSectionService owned;
    private MetadataDrivenAggregateSaveService aggregates;
    private TableSectionMetadataInfo descriptor;
    private final AtomicBoolean rejectContribution = new AtomicBoolean();

    @BeforeEach
    @SuppressWarnings("unchecked") // Mockito class token has no generic arguments.
    void setUp() throws Exception {
        factory = new Configuration().addAnnotatedClass(Root.class).addAnnotatedClass(Row.class)
            .addAnnotatedClass(Movement.class)
            .setProperty("hibernate.connection.url", "jdbc:h2:mem:ig4_" + UUID.randomUUID())
            .setProperty("hibernate.hbm2ddl.auto", "create-drop")
            .buildSessionFactory();
        em = SharedEntityManagerCreator.createSharedEntityManager(factory);
        transaction = new TransactionTemplate(new JpaTransactionManager(factory));
        validators = Validation.buildDefaultValidatorFactory();
        descriptor = new TableSectionMetadataInfo(Root.class, Row.class,
            Row.class.getAnnotation(TableSectionMetadata.class), Row.class.getDeclaredField("parent"),
            Row.class.getDeclaredField("line"), List.of(), List.of());
        SectionMetadataRegistry registry = mock(SectionMetadataRegistry.class);
        when(registry.forOwner(Root.class)).thenReturn(List.of(descriptor));
        RlsPolicyEnforcer rls = mock(RlsPolicyEnforcer.class);
        when(rls.prepareRead(eq(Root.class), any(EntityManager.class))).thenReturn(true);
        owned = new GenericOwnedSectionService(validators.getValidator(), new MetadataResolver(), registry, rls);
        ReflectionTestUtils.setField(owned, "entityManager", em);
        BaseService<Root, Long> rootService = mock(BaseService.class);
        when(rootService.save(any(Root.class))).thenAnswer(call -> {
            Root root = call.getArgument(0);
            if (root.getId() == null) {
                em.persist(root);
                return root;
            }
            return em.merge(root);
        });
        ServiceLocator locator = mock(ServiceLocator.class);
        when(locator.<Root, Long>findService(Root.class)).thenReturn(rootService);
        AggregateSaveContributionParticipant participant = new AggregateSaveContributionParticipant() {
            public boolean supports(Class<?> rootType, AggregateSaveContribution contribution) {
                return rootType == Root.class && contribution instanceof MovementContribution;
            }
            public void apply(IdentifiableEntity<Long> root, AggregateSaveContribution contribution,
                    AggregateSaveParticipantContext context) {
                em.persist(((MovementContribution) contribution).movement());
                em.flush();
                if (rejectContribution.get()) throw new ValidationException("late contribution veto");
            }
        };
        aggregates = new MetadataDrivenAggregateSaveService(locator, registry, owned,
            mock(EntityEventPublisher.class), new EntityLifecycleRegistry(List.of()), List.of(participant));
    }

    @AfterEach
    void close() {
        if (validators != null) validators.close();
        if (factory != null) factory.close();
    }

    @Test
    void commitsRootOwnedRowsAndContributionWithIndependentLongIds() {
        Root root = new Root();
        Row row = new Row();
        Movement movement = new Movement();
        transaction.executeWithoutResult(status -> save(root, row, movement));
        assertThat(root.getId()).isPositive();
        assertThat(row.getId()).isPositive();
        assertThat(movement.getId()).isPositive();
        assertThat(row.parent).isSameAs(root);
        assertThat(row.line).isEqualTo(1);
        assertThat(root.getVersion()).isNotNull();
        assertCounts(1, 1, 1);
        transaction.executeWithoutResult(status -> {
            List<Row> loaded = owned.findByParent(root, descriptor);
            assertThat(loaded).hasSize(1);
            assertThat(loaded.getFirst().getId()).isEqualTo(row.getId());
        });
    }

    @Test
    void lateVetoRestoresCallerStateAndSameObjectsCanBeRetried() {
        Root root = new Root();
        Row row = new Row();
        row.line = 17;
        row.title = "keep this edit";
        Movement movement = new Movement();
        rejectContribution.set(true);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> save(root, row, movement)))
            .isInstanceOf(ValidationException.class).hasMessageContaining("late contribution veto");
        assertThat(root.getId()).isNull();
        assertThat(root.getVersion()).isNull();
        assertThat(row.getId()).isNull();
        assertThat(row.getVersion()).isNull();
        assertThat(row.parent).isNull();
        assertThat(row.line).isEqualTo(17);
        assertThat(row.title).isEqualTo("keep this edit");
        assertThat(movement.getId()).isNull();
        assertThat(movement.getVersion()).isNull();
        assertCounts(0, 0, 0);
        rejectContribution.set(false);
        transaction.executeWithoutResult(status -> save(root, row, movement));
        assertCounts(1, 1, 1);
    }

    @Test
    void ownedReplaceMergesExistingRowsRemovesMissingRowsAndRejectsStaleOwner() {
        Root root = new Root();
        Row retained = new Row();
        Row removed = new Row();
        transaction.executeWithoutResult(status -> aggregates.save(root,
            List.of(MetadataDrivenAggregateSaveService.SectionInput.attached(
                Row.class, List.of(retained, removed))), EventSource.SYSTEM));
        Long originalVersion = root.getVersion();
        Long retainedId = retained.getId();
        retained.title = "detached edit";
        Row added = new Row();
        transaction.executeWithoutResult(status -> owned.replaceAll(root, List.of(retained, added), descriptor));
        transaction.executeWithoutResult(status -> {
            List<Row> rows = owned.findByParent(root, descriptor);
            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(Row::getId).contains(retainedId, added.getId())
                .doesNotContain(removed.getId());
            assertThat(em.find(Row.class, retainedId).title).isEqualTo("detached edit");
            assertThat(em.find(Root.class, root.getId()).getVersion()).isGreaterThan(originalVersion);
        });
        // The detached input still carries its original version.
        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
            owned.replaceAll(root, List.of(retained), descriptor)))
            .isInstanceOf(jakarta.persistence.OptimisticLockException.class)
            .hasMessageContaining("Stale aggregate version");
        assertCounts(1, 2, 0);
    }

    @Test
    void outerRollbackAfterRepeatedSavesRestoresEarliestIdentity() {
        Root root = new Root();
        Row row = new Row();
        transaction.executeWithoutResult(status -> {
            aggregates.save(root, List.of(MetadataDrivenAggregateSaveService.SectionInput.attached(
                Row.class, List.of(row))), EventSource.SYSTEM);
            aggregates.save(root, List.of(MetadataDrivenAggregateSaveService.SectionInput.attached(
                Row.class, List.of(row))), EventSource.SYSTEM);
            assertThat(row.getId()).isNotNull();
            status.setRollbackOnly();
        });
        assertThat(root.getId()).isNull();
        assertThat(root.getVersion()).isNull();
        assertThat(row.getId()).isNull();
        assertThat(row.parent).isNull();
        assertThat(row.line).isNull();
        assertCounts(0, 0, 0);
    }

    @Test
    void rollbackUnwrapsRealHibernateProxyAndRestoresImplementationVersion() {
        Root root = new Root();
        transaction.executeWithoutResult(status -> em.persist(root));
        Long id = root.getId();
        transaction.executeWithoutResult(status -> {
            Root proxy = em.getReference(Root.class, id);
            assertThat(proxy).isInstanceOf(HibernateProxy.class);
            Root target = (Root) AggregateSaveRollbackState.requireLongIdentity(proxy);
            Long version = target.getVersion();
            AggregateSaveRollbackState.current().capture(proxy, null);
            target.setVersion(99L);
            target.title = "keep proxy edit";
            status.setRollbackOnly();
            statusBeforeRollbackTarget = target;
            statusBeforeRollbackVersion = version;
        });
        assertThat(statusBeforeRollbackTarget.getId()).isEqualTo(id);
        assertThat(statusBeforeRollbackTarget.getVersion()).isEqualTo(statusBeforeRollbackVersion);
        assertThat(statusBeforeRollbackTarget.title).isEqualTo("keep proxy edit");
    }

    private Root statusBeforeRollbackTarget;
    private Long statusBeforeRollbackVersion;

    private void save(Root root, Row row, Movement movement) {
        aggregates.save(root, List.of(MetadataDrivenAggregateSaveService.SectionInput.attached(
            Row.class, List.of(row))), List.of(new MovementContribution(movement)), EventSource.SYSTEM);
    }

    private void assertCounts(long roots, long rows, long movements) {
        transaction.executeWithoutResult(status -> {
            assertThat(em.createQuery("select count(r) from Ig4Root r", Long.class).getSingleResult()).isEqualTo(roots);
            assertThat(em.createQuery("select count(r) from Ig4Row r", Long.class).getSingleResult()).isEqualTo(rows);
            assertThat(em.createQuery("select count(r) from Ig4Movement r", Long.class).getSingleResult()).isEqualTo(movements);
        });
    }

    private record MovementContribution(Movement movement) implements AggregateSaveContribution {
        public String key() { return "movement"; }
        public List<AggregateSection> eventSections() {
            return List.of(AggregateSection.attached(Movement.class, List.of(movement)));
        }
    }
}
