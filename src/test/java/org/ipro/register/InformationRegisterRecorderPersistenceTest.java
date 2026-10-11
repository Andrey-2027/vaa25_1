package org.ipro.register;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PessimisticLockException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.MetadataResolver;
import org.ipro.crud.AggregateSaveParticipantContext;
import org.ipro.crud.BaseService;
import org.ipro.crud.BaseEntity;
import org.ipro.crud.GenericOwnedSectionService;
import org.ipro.crud.MetadataDrivenAggregateSaveService;
import org.ipro.crud.ServiceLocator;
import org.ipro.events.EntityEventPublisher;
import org.ipro.events.EventSource;
import org.ipro.lifecycle.EntityLifecycleRegistry;
import org.ipro.lifecycle.EntityLifecycle;
import org.ipro.lifecycle.EntitySaveContext;
import org.ipro.events.EventContext;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsPolicyEnforcer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.register.persistence.RecorderOrderClock;
import org.ipro.registerfixtures.RecorderFixtureDocumentASection;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.ipro.registerfixtures.RecorderPersistenceFixtures.*;

/** Hibernate/H2 checks for all four register persistence models and the recorder write guard. */
class InformationRegisterRecorderPersistenceTest {
    private static final LocalDateTime DAY = LocalDateTime.of(2026, 10, 5, 0, 0);

    private StandardServiceRegistry serviceRegistry;
    private EntityManagerFactory entityManagerFactory;
    private ValidatorFactory validatorFactory;
    private RegisterRegistry registerRegistry;
    private RecorderTypeRegistry recorderTypeRegistry;
    private RecorderSetWriter writer;
    private JpaTransactionManager transactionManager;
    private RlsDimensionRegistry rlsDimensions;
    private org.ipro.rls.RlsStatementGuard statementGuard;

    @BeforeEach
    void createIsolatedDatabase() {
        rlsDimensions = mock(RlsDimensionRegistry.class);
        when(rlsDimensions.dimensions()).thenReturn(Set.of());
        when(rlsDimensions.filterableDimensionsByTable()).thenReturn(Map.of());
        statementGuard = new org.ipro.rls.RlsStatementGuard(rlsDimensions, true);
        serviceRegistry = new StandardServiceRegistryBuilder()
            .applySetting("hibernate.connection.driver_class", "org.h2.Driver")
            .applySetting("hibernate.connection.url", "jdbc:h2:mem:register_" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1")
            .applySetting("hibernate.connection.username", "sa")
            .applySetting("hibernate.connection.password", "")
            .applySetting("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
            .applySetting("hibernate.hbm2ddl.auto", "create-drop")
            .applySetting("hibernate.show_sql", "false")
            .applySetting("hibernate.session_factory.statement_inspector",
                (StatementInspector) sql -> {
                    statementGuard.audit(sql);
                    return sql;
                })
            .build();
        try {
            entityManagerFactory = new MetadataSources(serviceRegistry)
                .addAnnotatedClass(RecorderFixtureDocumentA.class)
                .addAnnotatedClass(RecorderFixtureDocumentASection.class)
                .addAnnotatedClass(RecorderFixtureDocumentB.class)
                .addAnnotatedClass(NonperiodicRegisterFixture.class)
                .addAnnotatedClass(PeriodicRegisterFixture.class)
                .addAnnotatedClass(RecordedRegisterFixture.class)
                .addAnnotatedClass(RecordedPeriodicRegisterFixture.class)
                .addAnnotatedClass(RecorderOrderClock.class)
                .buildMetadata()
                .buildSessionFactory();
            validatorFactory = Validation.buildDefaultValidatorFactory();
            var catalog = new ManagedEntityCatalog(entityManagerFactory);
            recorderTypeRegistry = new RecorderTypeRegistry(catalog, entityManagerFactory, new MetadataResolver());
            registerRegistry = new RegisterRegistry(catalog, recorderTypeRegistry);
            registerRegistry.initialize();
            writer = new RecorderSetWriter(registerRegistry, recorderTypeRegistry,
                validatorFactory.getValidator(), 1_000);
            transactionManager = new JpaTransactionManager(entityManagerFactory);
            new RecorderOrderClockBootstrap(entityManagerFactory, transactionManager)
                .initialize(registerRegistry.descriptors());
        } catch (RuntimeException | Error failure) {
            if (validatorFactory != null) validatorFactory.close();
            if (entityManagerFactory != null) entityManagerFactory.close();
            StandardServiceRegistryBuilder.destroy(serviceRegistry);
            throw failure;
        }
    }

    @AfterEach
    void closeIsolatedDatabase() {
        if (validatorFactory != null) validatorFactory.close();
        if (entityManagerFactory != null) entityManagerFactory.close();
        if (serviceRegistry != null) StandardServiceRegistryBuilder.destroy(serviceRegistry);
    }

    @Test
    void allFourModelsPassMetadataValidationAndRoundTripThroughJpa() {
        assertThat(RegisterMetadataValidator.validate(NonperiodicRegisterFixture.class)).isEmpty();
        assertThat(RegisterMetadataValidator.validate(PeriodicRegisterFixture.class)).isEmpty();
        assertThat(RegisterMetadataValidator.validate(RecordedRegisterFixture.class)).isEmpty();
        assertThat(RegisterMetadataValidator.validate(RecordedPeriodicRegisterFixture.class)).isEmpty();

        RecorderFixtureDocumentA documentA = new RecorderFixtureDocumentA();
        RecorderFixtureDocumentB documentB = new RecorderFixtureDocumentB();
        NonperiodicRegisterFixture standalone = new NonperiodicRegisterFixture("item-a", 100);
        PeriodicRegisterFixture standalonePeriodic = new PeriodicRegisterFixture(
            "item-a", DAY.plusHours(13).plusNanos(987_000_000), 200);
        RecordedRegisterFixture recorded = new RecordedRegisterFixture("item-a",
            new RecorderRef("fixture-document-a", 1L), 7, 41L, 300);
        RecordedPeriodicRegisterFixture recordedPeriodicA1 = recordedPeriodic(
            "fixture-document-a", 1L, 1, 51L, DAY.plusHours(9), 400);
        RecordedPeriodicRegisterFixture recordedPeriodicA2 = recordedPeriodic(
            "fixture-document-a", 1L, 2, 52L, DAY.plusHours(9), 401);
        RecordedPeriodicRegisterFixture recordedPeriodicB1 = recordedPeriodic(
            "fixture-document-b", 1L, 1, 61L, DAY.plusHours(9), 402);

        persistDocumentsAndRows(documentA, documentB, standalone, standalonePeriodic,
            recorded, recordedPeriodicA1, recordedPeriodicA2, recordedPeriodicB1);

        assertThat(documentA.getId()).isEqualTo(documentB.getId());
        assertThat(standalone.getId()).isNotNull();
        assertThat(standalonePeriodic.getId()).isNotNull();
        assertThat(recorded.getId()).isNotNull();

        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            NonperiodicRegisterFixture loadedStandalone = entityManager.find(
                NonperiodicRegisterFixture.class, standalone.getId());
            PeriodicRegisterFixture loadedStandalonePeriodic = entityManager.find(
                PeriodicRegisterFixture.class, standalonePeriodic.getId());
            RecordedRegisterFixture loadedRecorded = entityManager.find(
                RecordedRegisterFixture.class, recorded.getId());
            RecordedPeriodicRegisterFixture loadedRecordedPeriodic = entityManager.find(
                RecordedPeriodicRegisterFixture.class, recordedPeriodicA1.getId());

            assertThat(loadedStandalone.getItemKey()).isEqualTo("item-a");
            assertThat(loadedStandalone.getAmount()).isEqualTo(100);
            assertThat(loadedStandalonePeriodic.getPeriod()).isEqualTo(DAY);
            assertThat(loadedStandalonePeriodic.getAmount()).isEqualTo(200);
            assertThat(loadedRecorded.getRecorder().getTypeCode()).isEqualTo("fixture-document-a");
            assertThat(loadedRecorded.getRecorder().getId()).isEqualTo(1L);
            assertThat(loadedRecorded.getLineNumber()).isEqualTo(7);
            assertThat(loadedRecorded.getRecordingOrder()).isEqualTo(41L);
            assertThat(loadedRecorded.getAmount()).isEqualTo(300);
            assertThat(loadedRecordedPeriodic.getPeriod()).isEqualTo(DAY.plusHours(9));
            assertThat(loadedRecordedPeriodic.getRecorder().getTypeCode()).isEqualTo("fixture-document-a");
            assertThat(loadedRecordedPeriodic.getLineNumber()).isEqualTo(1);
            assertThat(loadedRecordedPeriodic.getRecordingOrder()).isEqualTo(51L);

            // Equal numeric ids and line numbers remain distinct across recorder types.
            assertThat(documentA.getId()).isEqualTo(documentB.getId());
            assertThat(entityManager.find(RecordedPeriodicRegisterFixture.class,
                recordedPeriodicB1.getId()).getRecorder().getTypeCode()).isEqualTo("fixture-document-b");
            // Same dimensions/period are legal for different lines of the same document.
            assertThat(entityManager.find(RecordedPeriodicRegisterFixture.class,
                recordedPeriodicA2.getId()).getLineNumber()).isEqualTo(2);
        }
    }

    @Test
    void generatedUniqueKeysMatchTheFourRegisterModes() {
        RecorderFixtureDocumentA documentA = persistDocumentA();
        RecorderFixtureDocumentB documentB = persistDocumentB();
        assertThat(documentA.getId()).isEqualTo(documentB.getId());

        persist(new NonperiodicRegisterFixture("item-a", 1), false);
        expectUniqueConstraint(new NonperiodicRegisterFixture("item-a", 2), false);

        persist(new PeriodicRegisterFixture("item-a", DAY.plusHours(2), 1), false);
        expectUniqueConstraint(new PeriodicRegisterFixture("item-a", DAY.plusHours(23), 2), false);
        // The standalone periodic key includes the normalized date; another date is allowed.
        persist(new PeriodicRegisterFixture("item-a", DAY.plusDays(1), 3), false);

        persist(new RecordedRegisterFixture("dimension-1", ref(documentA), 1, 1L, 1), true);
        expectUniqueConstraint(new RecordedRegisterFixture("different-dimension", ref(documentA), 1, 2L, 2), true);
        // Dimensions do not participate in a recorder-owned row identity.
        persist(new RecordedRegisterFixture("dimension-1", ref(documentA), 2, 3L, 3), true);
        persist(new RecordedRegisterFixture("dimension-1", ref(documentB), 1, 4L, 4), true);

        LocalDateTime firstPeriod = DAY.plusHours(5);
        persist(recordedPeriodic("fixture-document-a", documentA.getId(), 1, 11L, firstPeriod, 1), true);
        expectUniqueConstraint(recordedPeriodic("fixture-document-a", documentA.getId(), 1,
            12L, firstPeriod.plusDays(1), 2), true);
        // Period and dimensions are not part of the recorder-owned unique row key.
        persist(recordedPeriodic("fixture-document-a", documentA.getId(), 2, 13L, firstPeriod, 3), true);
        persist(recordedPeriodic("fixture-document-b", documentB.getId(), 1, 14L, firstPeriod, 4), true);
    }

    @Test
    void recorderGuardBlocksGenericInsertUpdateAndDeleteButAllowsScopedWrites() {
        RecorderFixtureDocumentA document = persistDocumentA();
        RecordedRegisterFixture seed = new RecordedRegisterFixture("item-a", ref(document), 1, 1L, 10);
        persist(seed, true);

        expectGuardFailure(entityManager -> {
            entityManager.persist(new RecordedRegisterFixture("item-b", ref(document), 2, 2L, 20));
            entityManager.flush();
        });
        expectGuardFailure(entityManager -> {
            entityManager.find(RecordedRegisterFixture.class, seed.getId()).setAmount(11);
            entityManager.flush();
        });
        expectGuardFailure(entityManager -> {
            entityManager.remove(entityManager.find(RecordedRegisterFixture.class, seed.getId()));
            entityManager.flush();
        });

        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            var transaction = entityManager.getTransaction();
            transaction.begin();
            withRecorderScope(seed, () -> {
                RecordedRegisterFixture current = entityManager.find(RecordedRegisterFixture.class, seed.getId());
                current.setAmount(12);
                entityManager.flush();
                entityManager.remove(current);
                entityManager.flush();
            });
            transaction.commit();
        }
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            assertThat(entityManager.find(RecordedRegisterFixture.class, seed.getId())).isNull();
        }
    }

    @Test
    void replacementSerializesRecorderVersionAndAllocatesOneOrderPerWholeSet() {
        RecorderFixtureDocumentA documentA = persistDocumentA();
        RecorderFixtureDocumentB documentB = persistDocumentB();
        assertThat(documentA.getId()).isEqualTo(documentB.getId());
        RecorderRef recorderA = ref(documentA);
        RecorderRef recorderB = ref(documentB);

        var first = replacePeriodic(recorderA, documentA.getVersion(), List.of(
            recordedPeriodic(recorderA.getTypeCode(), recorderA.getId(), 90, 900L, DAY.plusHours(8), 10),
            recordedPeriodic(recorderA.getTypeCode(), recorderA.getId(), 91, 901L, DAY.plusHours(8), 11)));
        assertThat(first.removedRows()).isZero();
        assertThat(first.insertedRows()).isEqualTo(2);
        assertThat(first.recordingOrder()).isEqualTo(1L);
        assertThat(first.documentVersion()).isEqualTo(1L);

        var second = replacePeriodic(recorderB, documentB.getVersion(), List.of(
            recordedPeriodic(recorderB.getTypeCode(), recorderB.getId(), 7, 777L, DAY.plusHours(8), 20)));
        assertThat(second.recordingOrder()).isEqualTo(2L);
        assertThat(second.documentVersion()).isEqualTo(1L);

        List<RecordedPeriodicRegisterFixture> savedA = periodicRows(recorderA);
        assertThat(savedA).extracting(RecordedPeriodicRegisterFixture::getLineNumber).containsExactly(1, 2);
        assertThat(savedA).extracting(RecordedPeriodicRegisterFixture::getRecordingOrder)
            .containsExactly(1L, 1L);
        List<RecordedPeriodicRegisterFixture> savedB = periodicRows(recorderB);
        assertThat(savedB).extracting(RecordedPeriodicRegisterFixture::getRecordingOrder).containsExactly(2L);

        assertThatThrownBy(() -> replacePeriodic(recorderA, 0L, List.of(
            recordedPeriodic(recorderA.getTypeCode(), recorderA.getId(), 1, 1L, DAY, 99))))
            .isInstanceOf(OptimisticLockException.class)
            .hasMessageContaining("RECORDER_VERSION_CONFLICT");
        assertThat(clockOrder(RecordedPeriodicRegisterFixture.class)).isEqualTo(2L);
        assertThat(periodicRows(recorderA)).hasSize(2);

        var cleared = replacePeriodic(recorderA, 1L, List.of());
        assertThat(cleared.removedRows()).isEqualTo(2);
        assertThat(cleared.insertedRows()).isZero();
        assertThat(cleared.recordingOrder()).isEqualTo(3L);
        assertThat(cleared.documentVersion()).isEqualTo(2L);
        assertThat(periodicRows(recorderA)).isEmpty();
        assertThat(periodicRows(recorderB)).hasSize(1);
    }

    @Test
    void failedValidationOrPartialInsertRollsBackRowsClockAndDocumentVersion() {
        RecorderFixtureDocumentA document = persistDocumentA();
        RecorderRef recorder = ref(document);
        replacePeriodic(recorder, document.getVersion(), List.of(
            recordedPeriodic(recorder.getTypeCode(), recorder.getId(), 1, 1L, DAY, 10)));

        assertThatThrownBy(() -> replacePeriodic(recorder, 1L, List.of(
            recordedPeriodic(recorder.getTypeCode(), recorder.getId(), 8, 8L, DAY, 20),
            recordedPeriodic(recorder.getTypeCode(), recorder.getId(), 9, 9L, DAY, -1))))
            .isInstanceOf(RuntimeException.class);
        assertThat(periodicRows(recorder)).extracting(RecordedPeriodicRegisterFixture::getAmount)
            .containsExactly(10);
        assertThat(clockOrder(RecordedPeriodicRegisterFixture.class)).isEqualTo(1L);
        assertThat(documentVersion(RecorderFixtureDocumentA.class, document.getId())).isEqualTo(1L);

        assertThatThrownBy(() -> replacePeriodic(recorder, 1L, List.of(
            recordedPeriodic(recorder.getTypeCode(), recorder.getId(), 1, 1L, DAY, null))))
            .isInstanceOf(org.ipro.crud.ValidationException.class)
            .hasMessageContaining("RECORDER_SET_INVALID");
        assertThat(periodicRows(recorder)).extracting(RecordedPeriodicRegisterFixture::getAmount)
            .containsExactly(10);
        assertThat(clockOrder(RecordedPeriodicRegisterFixture.class)).isEqualTo(1L);
        assertThat(documentVersion(RecorderFixtureDocumentA.class, document.getId())).isEqualTo(1L);
    }

    @Test
    void deletingOneRecorderSetIsExactAndRollsBackWithTheCallerTransaction() {
        RecorderFixtureDocumentA documentA = persistDocumentA();
        RecorderFixtureDocumentB documentB = persistDocumentB();
        RecorderRef recorderA = ref(documentA);
        RecorderRef recorderB = ref(documentB);
        replacePeriodic(recorderA, documentA.getVersion(), List.of(
            recordedPeriodic(recorderA.getTypeCode(), recorderA.getId(), 1, 1L, DAY, 10)));
        replacePeriodic(recorderB, documentB.getVersion(), List.of(
            recordedPeriodic(recorderB.getTypeCode(), recorderB.getId(), 1, 2L, DAY, 20)));

        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            var transaction = entityManager.getTransaction();
            transaction.begin();
            assertThat(writer.deleteForRecorder(entityManager,
                RecordedPeriodicRegisterFixture.class, recorderA)).isEqualTo(1);
            transaction.rollback();
        }
        assertThat(periodicRows(recorderA)).extracting(RecordedPeriodicRegisterFixture::getAmount)
            .containsExactly(10);
        assertThat(periodicRows(recorderB)).extracting(RecordedPeriodicRegisterFixture::getAmount)
            .containsExactly(20);

        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            var transaction = entityManager.getTransaction();
            transaction.begin();
            assertThat(writer.deleteForRecorder(entityManager,
                RecordedPeriodicRegisterFixture.class, recorderA)).isEqualTo(1);
            transaction.commit();
        }
        assertThat(periodicRows(recorderA)).isEmpty();
        assertThat(periodicRows(recorderB)).extracting(RecordedPeriodicRegisterFixture::getAmount)
            .containsExactly(20);
    }

    @Test
    void hibernateRecorderSnapshotSqlPassesTheNarrowRlsGuardForReplaceAndDelete() {
        var activator = testRlsActivator();
        writer = new RecorderSetWriter(registerRegistry, recorderTypeRegistry,
            validatorFactory.getValidator(), 1_000, activator);

        RecorderFixtureDocumentA document = persistDocumentA();
        RecorderRef recorder = ref(document);
        replacePeriodic(recorder, document.getVersion(), List.of(
            recordedPeriodic(recorder.getTypeCode(), recorder.getId(), 1, 1L, DAY, 10)));

        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            var transaction = entityManager.getTransaction();
            transaction.begin();
            int removed = writer.deleteForRecorder(entityManager,
                RecordedPeriodicRegisterFixture.class, recorder);
            transaction.commit();
            assertThat(removed).isEqualTo(1);
        }

        assertThat(periodicRows(recorder)).isEmpty();
    }

    @Test
    void aggregateRecorderContributionAdvancesVersionOnceAndRollsBackAtomically() {
        RecorderFixtureDocumentA document = persistDocumentA();
        RecorderRef recorder = ref(document);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            EntityManager entityManager = EntityManagerFactoryUtils
                .getTransactionalEntityManager(entityManagerFactory);
            var managed = entityManager.find(RecorderFixtureDocumentA.class, document.getId());
            RecorderSetContributionParticipant participant = new RecorderSetContributionParticipant(
                entityManager, registerRegistry, recorderTypeRegistry, validatorFactory.getValidator(),
                testRlsActivator(), request -> { });
            participant.apply(managed, new RecorderSetContribution(RecordedPeriodicRegisterFixture.class,
                    List.of(recordedPeriodic(recorder.getTypeCode(), recorder.getId(), 1, 1L, DAY, 10))),
                new AggregateSaveParticipantContext(false, managed.getVersion()));
            assertThat(managed.getVersion()).isEqualTo(1L);
            throw new IllegalStateException("simulated later aggregate failure");
        })).isInstanceOf(IllegalStateException.class)
            .hasMessage("simulated later aggregate failure");

        assertThat(documentVersion(RecorderFixtureDocumentA.class, document.getId())).isZero();
        assertThat(periodicRows(recorder)).isEmpty();

        transactions.executeWithoutResult(status -> {
            EntityManager entityManager = EntityManagerFactoryUtils
                .getTransactionalEntityManager(entityManagerFactory);
            var managed = entityManager.find(RecorderFixtureDocumentA.class, document.getId());
            RecorderSetContributionParticipant participant = new RecorderSetContributionParticipant(
                entityManager, registerRegistry, recorderTypeRegistry, validatorFactory.getValidator(),
                testRlsActivator(), request -> { });
            participant.apply(managed, new RecorderSetContribution(RecordedPeriodicRegisterFixture.class,
                    List.of(recordedPeriodic(recorder.getTypeCode(), recorder.getId(), 1, 1L, DAY, 10))),
                new AggregateSaveParticipantContext(false, managed.getVersion()));
        });

        assertThat(documentVersion(RecorderFixtureDocumentA.class, document.getId())).isEqualTo(1L);
        assertThat(periodicRows(recorder)).hasSize(1);
    }

    @Test
    void aggregateSaveRollsBackRootOwnedSectionAndAllRecorderSetsTogether() {
        RecorderFixtureDocumentA document = persistDocumentA();
        RecorderRef recorder = ref(document);
        persistOwnedSection(document.getId(), "before");
        seedBothRecordedRegisters(recorder, 10);
        Long savedVersion = documentVersion(RecorderFixtureDocumentA.class, document.getId());
        assertThat(savedVersion).isEqualTo(2L);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            EntityManager entityManager = EntityManagerFactoryUtils
                .getTransactionalEntityManager(entityManagerFactory);
            RecorderFixtureDocumentA managed = entityManager.find(
                RecorderFixtureDocumentA.class, document.getId());
            aggregateSaveService(entityManager, true).save(managed,
                List.of(MetadataDrivenAggregateSaveService.SectionInput.attached(
                    RecorderFixtureDocumentASection.class, List.of(new RecorderFixtureDocumentASection("after")))),
                List.of(
                    new RecorderSetContribution(RecordedRegisterFixture.class,
                        List.of(new RecordedRegisterFixture("item-a", recorder, 1, 2L, 20))),
                    new RecorderSetContribution(RecordedPeriodicRegisterFixture.class,
                        List.of(recordedPeriodic(recorder.getTypeCode(), recorder.getId(), 1, 2L, DAY, 20)))),
                EventSource.SYSTEM);
        })).isInstanceOf(IllegalStateException.class)
            .hasMessage("simulated aggregate after-save failure");

        assertThat(documentVersion(RecorderFixtureDocumentA.class, document.getId())).isEqualTo(savedVersion);
        assertThat(ownedSectionValues(document.getId())).containsExactly("before");
        assertThat(recordedRows(recorder)).extracting(RecordedRegisterFixture::getAmount)
            .containsExactly(10);
        assertThat(periodicRows(recorder)).extracting(RecordedPeriodicRegisterFixture::getAmount)
            .containsExactly(10);
        assertThat(clockOrder(RecordedRegisterFixture.class)).isEqualTo(1L);
        assertThat(clockOrder(RecordedPeriodicRegisterFixture.class)).isEqualTo(1L);

        transactions.executeWithoutResult(status -> {
            EntityManager entityManager = EntityManagerFactoryUtils
                .getTransactionalEntityManager(entityManagerFactory);
            RecorderFixtureDocumentA managed = entityManager.find(
                RecorderFixtureDocumentA.class, document.getId());
            aggregateSaveService(entityManager, false).save(managed,
                List.of(MetadataDrivenAggregateSaveService.SectionInput.attached(
                    RecorderFixtureDocumentASection.class, List.of(new RecorderFixtureDocumentASection("after")))),
                List.of(
                    new RecorderSetContribution(RecordedRegisterFixture.class,
                        List.of(new RecordedRegisterFixture("item-a", recorder, 1, 2L, 20))),
                    new RecorderSetContribution(RecordedPeriodicRegisterFixture.class,
                        List.of(recordedPeriodic(recorder.getTypeCode(), recorder.getId(), 1, 2L, DAY, 20)))),
                EventSource.SYSTEM);
        });

        assertThat(documentVersion(RecorderFixtureDocumentA.class, document.getId())).isEqualTo(savedVersion + 1L);
        assertThat(ownedSectionValues(document.getId())).containsExactly("after");
        assertThat(recordedRows(recorder)).extracting(RecordedRegisterFixture::getAmount)
            .containsExactly(20);
        assertThat(periodicRows(recorder)).extracting(RecordedPeriodicRegisterFixture::getAmount)
            .containsExactly(20);
        assertThat(clockOrder(RecordedRegisterFixture.class)).isEqualTo(2L);
        assertThat(clockOrder(RecordedPeriodicRegisterFixture.class)).isEqualTo(2L);
    }

    @Test
    void registryDrivenDeleteParticipantRemovesAllOwnedRegistersAndRollsBackWithRoot() {
        RecorderFixtureDocumentA documentA = persistDocumentA();
        RecorderFixtureDocumentB documentB = persistDocumentB();
        RecorderRef recorderA = ref(documentA);
        RecorderRef recorderB = ref(documentB);
        persistOwnedSection(documentA.getId(), "owned-a");
        seedBothRecordedRegisters(recorderA, 10);
        seedBothRecordedRegisters(recorderB, 20);

        RlsFilterActivator activator = cleanupWithoutRlsBypass(mock(RlsFilterActivator.class));
        assertThatThrownBy(() -> deleteThroughParticipant(recorderA, activator, true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("REFERENCE_CHECK_FAILED");
        assertThat(recordedRows(recorderA)).hasSize(1);
        assertThat(periodicRows(recorderA)).hasSize(1);
        assertThat(ownedSectionValues(documentA.getId())).containsExactly("owned-a");
        assertThat(documentExists(RecorderFixtureDocumentA.class, documentA.getId())).isTrue();

        deleteThroughParticipant(recorderA, activator, false);

        assertThat(recordedRows(recorderA)).isEmpty();
        assertThat(periodicRows(recorderA)).isEmpty();
        assertThat(ownedSectionValues(documentA.getId())).isEmpty();
        assertThat(documentExists(RecorderFixtureDocumentA.class, documentA.getId())).isFalse();
        assertThat(recordedRows(recorderB)).extracting(RecordedRegisterFixture::getAmount)
            .containsExactly(20);
        assertThat(periodicRows(recorderB)).extracting(RecordedPeriodicRegisterFixture::getAmount)
            .containsExactly(20);
    }

    private RlsFilterActivator testRlsActivator() {
        var currentUser = mock(RlsCurrentUser.class);
        when(currentUser.requireAuthenticatedUsername()).thenReturn("register-test-user");
        return new RlsFilterActivator(rlsDimensions, null, currentUser);
    }

    private MetadataDrivenAggregateSaveService aggregateSaveService(EntityManager entityManager,
                                                                    boolean failAfterSave) {
        return aggregateSaveService(entityManager, failAfterSave, List.of());
    }

    private MetadataDrivenAggregateSaveService aggregateSaveService(EntityManager entityManager,
            boolean failAfterSave, List<EntityLifecycle<?>> lifecycles) {
        MetadataResolver metadataResolver = new MetadataResolver();
        SectionMetadataRegistry sections = new SectionMetadataRegistry("org.ipro.registerfixtures", metadataResolver);
        sections.afterPropertiesSet();
        RlsPolicyEnforcer rlsPolicyEnforcer = mock(RlsPolicyEnforcer.class);
        when(rlsPolicyEnforcer.prepareRead(eq(RecorderFixtureDocumentA.class), any(EntityManager.class)))
            .thenReturn(true);
        GenericOwnedSectionService sectionService = new GenericOwnedSectionService(
            validatorFactory.getValidator(), metadataResolver, sections, rlsPolicyEnforcer);
        ReflectionTestUtils.setField(sectionService, "entityManager", entityManager);

        ServiceLocator serviceLocator = mock(ServiceLocator.class);
        BaseService rootService = mock(BaseService.class);
        org.mockito.Mockito.doReturn(rootService).when(serviceLocator)
            .findService(RecorderFixtureDocumentA.class);
        doAnswer(invocation -> invocation.getArgument(0)).when(rootService).save(any());

        EntityEventPublisher eventPublisher = mock(EntityEventPublisher.class);
        when(eventPublisher.openAggregateOperation(any()))
            .thenReturn(mock(EntityEventPublisher.EventScope.class));
        if (failAfterSave) {
            doThrow(new IllegalStateException("simulated aggregate after-save failure"))
                .when(eventPublisher).publishSaved(any(), any());
        }
        RecorderSetContributionParticipant recorderParticipant = new RecorderSetContributionParticipant(
            entityManager, registerRegistry, recorderTypeRegistry, validatorFactory.getValidator(),
            testRlsActivator(), request -> { });
        return new MetadataDrivenAggregateSaveService(serviceLocator, sections, sectionService,
            eventPublisher, new EntityLifecycleRegistry(lifecycles), List.of(recorderParticipant));
    }

    @Test
    void lifecycleAddAssignsTechnicalFieldsAndPreservesExistingIdsAndOrder() {
        RecorderFixtureDocumentA document = persistDocumentA();
        RecorderRef recorder = ref(document);
        replacePeriodic(recorder, 0L, List.of(recordedPeriodic(
            recorder.getTypeCode(), recorder.getId(), 1, 1L, DAY, 10)));
        Long originalId = periodicRows(recorder).getFirst().getId();
        withRegisterLifecycle(document, request -> { }, records -> {
            var first = records.add(RecordedPeriodicRegisterFixture.class, List.of(newRow(20)));
            var second = records.add(RecordedPeriodicRegisterFixture.class, List.of(newRow(30)));
            assertThat(first.getFirst().getId()).isNotNull();
            assertThat(first.getFirst().getLineNumber()).isEqualTo(2);
            assertThat(second.getFirst().getLineNumber()).isEqualTo(3);
            assertThat(first.getFirst().getRecorder()).isEqualTo(recorder);
        });
        assertThat(periodicRows(recorder)).extracting(RecordedPeriodicRegisterFixture::getAmount)
            .containsExactly(10, 20, 30);
        assertThat(periodicRows(recorder).getFirst().getId()).isEqualTo(originalId);
        assertThat(periodicRows(recorder)).extracting(RecordedPeriodicRegisterFixture::getRecordingOrder)
            .containsExactly(1L, 1L, 1L);
        assertThat(clockOrder(RecordedPeriodicRegisterFixture.class)).isEqualTo(1L);
    }

    @Test
    void registerOnlyLifecycleWriteAdvancesUnchangedOwnerVersionExactlyOnce() {
        RecorderFixtureDocumentA document = persistDocumentA();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            EntityManager em = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
            RegisterRecords api = registerRecords(em, request -> { });
            EntityLifecycle<RecorderFixtureDocumentA> lifecycle = new EntityLifecycle<>() {
                @Override public Class<RecorderFixtureDocumentA> entityType() { return RecorderFixtureDocumentA.class; }
                @Override public void onSave(EntitySaveContext<RecorderFixtureDocumentA> context) {
                    var records = api.forRecorder(context);
                    records.add(RecordedPeriodicRegisterFixture.class, List.of(newRow(10)));
                    records.add(RecordedPeriodicRegisterFixture.class, List.of(newRow(20)));
                }
            };
            var managed = em.find(RecorderFixtureDocumentA.class, document.getId());
            aggregateSaveService(em, false, List.of(lifecycle)).save(managed, List.of(), EventSource.SYSTEM);
        });
        assertThat(documentVersion(RecorderFixtureDocumentA.class, document.getId())).isEqualTo(1L);
        assertThat(clockOrder(RecordedPeriodicRegisterFixture.class)).isEqualTo(1L);
        assertThat(periodicRows(ref(document))).hasSize(2);
    }

    @Test
    void lifecycleClearThenAddCreatesNewIdsAndOneNewOrderForAllAdds() {
        RecorderFixtureDocumentA document = persistDocumentA();
        RecorderRef recorder = ref(document);
        replacePeriodic(recorder, 0L, List.of(recordedPeriodic(
            recorder.getTypeCode(), recorder.getId(), 1, 1L, DAY, 10)));
        Long originalId = periodicRows(recorder).getFirst().getId();
        withRegisterLifecycle(document, request -> { }, records -> {
            assertThat(records.clear(RecordedPeriodicRegisterFixture.class)).isEqualTo(1);
            records.add(RecordedPeriodicRegisterFixture.class, List.of(newRow(20)));
            records.add(RecordedPeriodicRegisterFixture.class, List.of(newRow(30)));
        });
        assertThat(periodicRows(recorder)).extracting(RecordedPeriodicRegisterFixture::getId)
            .doesNotContain(originalId);
        assertThat(periodicRows(recorder)).extracting(RecordedPeriodicRegisterFixture::getLineNumber)
            .containsExactly(1, 2);
        assertThat(periodicRows(recorder)).extracting(RecordedPeriodicRegisterFixture::getRecordingOrder)
            .containsExactly(2L, 2L);
        assertThat(clockOrder(RecordedPeriodicRegisterFixture.class)).isEqualTo(2L);
    }

    @Test
    void lifecycleDeleteRemovesOnlySelectedRowAndAppendDoesNotRenumberSurvivors() {
        RecorderFixtureDocumentA document = persistDocumentA();
        RecorderRef recorder = ref(document);
        replacePeriodic(recorder, 0L, List.of(
            recordedPeriodic(recorder.getTypeCode(), recorder.getId(), 1, 1L, DAY, 10),
            recordedPeriodic(recorder.getTypeCode(), recorder.getId(), 2, 1L, DAY, 20)));
        List<RecordedPeriodicRegisterFixture> before = periodicRows(recorder);
        List<RecorderSetWriteRequest> decisions = new ArrayList<>();
        withRegisterLifecycle(document, decisions::add, records -> {
            records.delete(RecordedPeriodicRegisterFixture.class, before.getFirst().getId());
            records.add(RecordedPeriodicRegisterFixture.class, List.of(newRow(30)));
        });
        assertThat(decisions.getFirst().existingRows()).hasSize(1);
        assertThat(((BaseEntity) decisions.getFirst().existingRows().getFirst()).getId())
            .isEqualTo(before.getFirst().getId());
        assertThat(periodicRows(recorder)).extracting(RecordedPeriodicRegisterFixture::getLineNumber)
            .containsExactly(2, 3);
        assertThat(periodicRows(recorder).getFirst().getId()).isEqualTo(before.get(1).getId());
        assertThat(clockOrder(RecordedPeriodicRegisterFixture.class)).isEqualTo(1L);
    }

    @Test
    void lifecycleDeleteForeignAndMissingIdsGiveTheSameRefusal() {
        RecorderFixtureDocumentA document = persistDocumentA();
        RecorderFixtureDocumentB other = persistDocumentB();
        replacePeriodic(ref(other), 0L, List.of(recordedPeriodic(
            ref(other).getTypeCode(), other.getId(), 1, 1L, DAY, 20)));
        Long foreignId = periodicRows(ref(other)).getFirst().getId();
        for (Long id : List.of(foreignId, Long.MAX_VALUE)) {
            assertThatThrownBy(() -> withRegisterLifecycle(document, request -> { },
                records -> records.delete(RecordedPeriodicRegisterFixture.class, id)))
                .isInstanceOf(jakarta.persistence.EntityNotFoundException.class)
                .hasMessage("Запись регистра отсутствует или недоступна");
        }
        assertThat(periodicRows(ref(other))).hasSize(1);
    }

    @Test
    void caughtFailureAfterClearStillRollsBackTheWholeDocumentTransaction() {
        RecorderFixtureDocumentA document = persistDocumentA();
        RecorderRef recorder = ref(document);
        replacePeriodic(recorder, 0L, List.of(recordedPeriodic(
            recorder.getTypeCode(), recorder.getId(), 1, 1L, DAY, 10)));
        Long originalId = periodicRows(recorder).getFirst().getId();
        Long originalVersion = documentVersion(RecorderFixtureDocumentA.class, document.getId());
        assertThatThrownBy(() -> withRegisterLifecycle(document, request -> { }, records -> {
            records.clear(RecordedPeriodicRegisterFixture.class);
            try {
                records.add(RecordedPeriodicRegisterFixture.class, List.of(newRow(null)));
            } catch (org.ipro.crud.ValidationException ignored) {
                // A lifecycle must not turn a failed clear/add into a partial successful commit.
            }
        })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(periodicRows(recorder)).extracting(RecordedPeriodicRegisterFixture::getId)
            .containsExactly(originalId);
        assertThat(documentVersion(RecorderFixtureDocumentA.class, document.getId())).isEqualTo(originalVersion);
        assertThat(clockOrder(RecordedPeriodicRegisterFixture.class)).isEqualTo(1L);
    }

    @Test
    void clearAuthorizesTheCompleteSetBeforeRemovingAnyRow() {
        RecorderFixtureDocumentA document = persistDocumentA();
        RecorderRef recorder = ref(document);
        replacePeriodic(recorder, 0L, List.of(
            recordedPeriodic(recorder.getTypeCode(), recorder.getId(), 1, 1L, DAY, 10),
            recordedPeriodic(recorder.getTypeCode(), recorder.getId(), 2, 1L, DAY, 20)));
        assertThatThrownBy(() -> withRegisterLifecycle(document, request -> {
            assertThat(request.existingRows()).hasSize(2);
            throw new org.ipro.rls.RlsAccessDeniedException("second row denied");
        }, records -> records.clear(RecordedPeriodicRegisterFixture.class)))
            .isInstanceOf(org.ipro.rls.RlsAccessDeniedException.class);
        assertThat(periodicRows(recorder)).hasSize(2);
    }

    @Test
    void fabricatedContextAndCapturedHandleCannotOpenRecorderWrites() {
        RecorderFixtureDocumentA document = persistDocumentA();
        RegisterRecords.ForRecorder[] captured = new RegisterRecords.ForRecorder[1];
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            EntityManager entityManager = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
            var managed = entityManager.find(RecorderFixtureDocumentA.class, document.getId());
            RegisterRecords api = registerRecords(entityManager, request -> { });
            EventContext fake = EventContext.forEntity(RecorderFixtureDocumentA.class, document.getId(),
                EventSource.SYSTEM, "save:RecorderFixtureDocumentA");
            assertThatThrownBy(() -> api.forRecorder(new EntitySaveContext<>(managed, fake)))
                .hasMessageContaining("REGISTER_OWNER_WRITE_CONTEXT_REQUIRED");
            EntityLifecycle<RecorderFixtureDocumentA> lifecycle = new EntityLifecycle<>() {
                @Override public Class<RecorderFixtureDocumentA> entityType() { return RecorderFixtureDocumentA.class; }
                @Override public void onSave(EntitySaveContext<RecorderFixtureDocumentA> context) {
                    captured[0] = api.forRecorder(context);
                }
            };
            aggregateSaveService(entityManager, false, List.of(lifecycle)).save(managed, List.of(), EventSource.SYSTEM);
            assertThatThrownBy(() -> captured[0].clear(RecordedPeriodicRegisterFixture.class))
                .hasMessageContaining("REGISTER_OWNER_WRITE_CONTEXT_EXPIRED");
            status.setRollbackOnly();
        });
        assertThatThrownBy(() -> captured[0].clear(RecordedPeriodicRegisterFixture.class))
            .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    void appendCannotUseItsFlushScopeToUpdateAnotherExistingRow() {
        RecorderFixtureDocumentA document = persistDocumentA();
        RecorderRef recorder = ref(document);
        replacePeriodic(recorder, 0L, List.of(recordedPeriodic(
            recorder.getTypeCode(), recorder.getId(), 1, 1L, DAY, 10)));
        Long originalId = periodicRows(recorder).getFirst().getId();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            EntityManager em = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
            RecorderSetWriteAuthorizer authorizer = request -> {
                em.find(RecordedPeriodicRegisterFixture.class, originalId).setAmount(99);
            };
            runRegisterLifecycle(em, document.getId(), authorizer,
                records -> records.add(RecordedPeriodicRegisterFixture.class, List.of(newRow(20))));
        })).satisfies(failure -> assertThat(exceptionMessages(failure)).contains("RECORDER_SET_WRITE_REQUIRED"));
        assertThat(periodicRows(recorder)).extracting(RecordedPeriodicRegisterFixture::getAmount)
            .containsExactly(10);
    }

    @Test
    void clearAcceptsTheAuthorizedImplementationBehindAnExistingHibernateProxy() {
        RecorderFixtureDocumentA document = persistDocumentA();
        RecorderRef recorder = ref(document);
        replacePeriodic(recorder, 0L, List.of(recordedPeriodic(
            recorder.getTypeCode(), recorder.getId(), 1, 1L, DAY, 10)));
        Long rowId = periodicRows(recorder).getFirst().getId();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            EntityManager em = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
            em.getReference(RecordedPeriodicRegisterFixture.class, rowId);
            runRegisterLifecycle(em, document.getId(), request -> { },
                records -> assertThat(records.clear(RecordedPeriodicRegisterFixture.class)).isEqualTo(1));
        });
        assertThat(periodicRows(recorder)).isEmpty();
    }

    private RegisterRecords registerRecords(EntityManager em, RecorderSetWriteAuthorizer authorizer) {
        return new RegisterRecords(em, registerRegistry, recorderTypeRegistry, validatorFactory.getValidator(),
            testRlsActivator(), authorizer, transactionManager);
    }

    private void withRegisterLifecycle(RecorderFixtureDocumentA document, RecorderSetWriteAuthorizer authorizer,
                                       Consumer<RegisterRecords.ForRecorder> work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            EntityManager em = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
            runRegisterLifecycle(em, document.getId(), authorizer, work);
        });
    }

    private void runRegisterLifecycle(EntityManager em, Long documentId, RecorderSetWriteAuthorizer authorizer,
                                      Consumer<RegisterRecords.ForRecorder> work) {
        RegisterRecords api = registerRecords(em, authorizer);
        EntityLifecycle<RecorderFixtureDocumentA> lifecycle = new EntityLifecycle<>() {
            @Override public Class<RecorderFixtureDocumentA> entityType() { return RecorderFixtureDocumentA.class; }
            @Override public void onSave(EntitySaveContext<RecorderFixtureDocumentA> context) {
                context.entity().setNote("lifecycle changed root");
                work.accept(api.forRecorder(context));
            }
        };
        var managed = em.find(RecorderFixtureDocumentA.class, documentId);
        aggregateSaveService(em, false, List.of(lifecycle)).save(managed, List.of(), EventSource.SYSTEM);
    }

    private static RecordedPeriodicRegisterFixture newRow(Integer amount) {
        return new RecordedPeriodicRegisterFixture("item-a", null, null, null, DAY, amount);
    }

    private void persistOwnedSection(Long documentId, String value) {
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            var transaction = entityManager.getTransaction();
            transaction.begin();
            RecorderFixtureDocumentA document = entityManager.find(RecorderFixtureDocumentA.class, documentId);
            RecorderFixtureDocumentASection section = new RecorderFixtureDocumentASection(value);
            section.setDocument(document);
            section.setLineNumber(1);
            entityManager.persist(section);
            transaction.commit();
        }
    }

    private List<String> ownedSectionValues(Long documentId) {
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            return entityManager.createQuery("select section.value from "
                    + RecorderFixtureDocumentASection.class.getName()
                    + " section where section.document.id = :documentId order by section.lineNumber", String.class)
                .setParameter("documentId", documentId)
                .getResultList();
        }
    }

    @Test
    void retryExecutorUsesAFreshTransactionAndStopsAtItsConfiguredBound() {
        RecorderSetTransactionExecutor retry = new RecorderSetTransactionExecutor(
            new JpaTransactionManager(entityManagerFactory), 3, 0);
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> retry.execute(() -> {
            attempts.incrementAndGet();
            throw new PessimisticLockException("simulated database lock timeout");
        })).isInstanceOf(PessimisticLockException.class);

        assertThat(attempts).hasValue(3);
    }

    private void persistDocumentsAndRows(RecorderFixtureDocumentA documentA,
                                         RecorderFixtureDocumentB documentB,
                                         NonperiodicRegisterFixture standalone,
                                         PeriodicRegisterFixture standalonePeriodic,
                                         RecordedRegisterFixture recorded,
                                         RecordedPeriodicRegisterFixture... recordedPeriodic) {
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            var transaction = entityManager.getTransaction();
            transaction.begin();
            entityManager.persist(documentA);
            entityManager.persist(documentB);
            entityManager.flush();
            entityManager.persist(standalone);
            entityManager.persist(standalonePeriodic);
            withRecorderScope(recorded, () -> {
                entityManager.persist(recorded);
                entityManager.flush();
            });
            for (RecordedPeriodicRegisterFixture row : recordedPeriodic) {
                withRecorderScope(row, () -> {
                    entityManager.persist(row);
                    entityManager.flush();
                });
            }
            transaction.commit();
        }
    }

    private RecorderSetWriter.Result replacePeriodic(RecorderRef recorder, Long expectedVersion,
                                                     List<RecordedPeriodicRegisterFixture> rows) {
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            var transaction = entityManager.getTransaction();
            transaction.begin();
            try {
                RecorderSetWriter.Result result = writer.replaceForRecorder(entityManager,
                    RecordedPeriodicRegisterFixture.class, recorder, expectedVersion, rows);
                transaction.commit();
                return result;
            } catch (RuntimeException failure) {
                if (transaction.isActive()) transaction.rollback();
                throw failure;
            }
        }
    }

    private List<RecordedPeriodicRegisterFixture> periodicRows(RecorderRef recorder) {
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            var builder = entityManager.getCriteriaBuilder();
            var query = builder.createQuery(RecordedPeriodicRegisterFixture.class);
            var root = query.from(RecordedPeriodicRegisterFixture.class);
            var reference = root.get("recorder");
            query.select(root).where(
                builder.equal(reference.get("typeCode"), recorder.getTypeCode()),
                builder.equal(reference.get("id"), recorder.getId())
            ).orderBy(builder.asc(root.get("lineNumber")));
            return entityManager.createQuery(query).getResultList();
        }
    }

    private List<RecordedRegisterFixture> recordedRows(RecorderRef recorder) {
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            var builder = entityManager.getCriteriaBuilder();
            var query = builder.createQuery(RecordedRegisterFixture.class);
            var root = query.from(RecordedRegisterFixture.class);
            var reference = root.get("recorder");
            query.select(root).where(
                builder.equal(reference.get("typeCode"), recorder.getTypeCode()),
                builder.equal(reference.get("id"), recorder.getId())
            ).orderBy(builder.asc(root.get("lineNumber")));
            return entityManager.createQuery(query).getResultList();
        }
    }

    private void seedBothRecordedRegisters(RecorderRef recorder, int amount) {
        Long currentVersion = documentVersion(recorderTypeRegistry.resolveType(recorder), recorder.getId());
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            var transaction = entityManager.getTransaction();
            transaction.begin();
            var result = writer.replaceForRecorder(entityManager, RecordedRegisterFixture.class,
                recorder, currentVersion, List.of(new RecordedRegisterFixture(
                    "item-a", recorder, 1, 1L, amount)));
            transaction.commit();
            currentVersion = result.documentVersion();
        }
        replacePeriodic(recorder, currentVersion, List.of(recordedPeriodic(
            recorder.getTypeCode(), recorder.getId(), 1, 1L, DAY, amount)));
    }

    private RlsFilterActivator cleanupWithoutRlsBypass(RlsFilterActivator activator) {
        doAnswer(invocation -> {
            Supplier<?> query = invocation.getArgument(2);
            return query.get();
        }).when(activator).withDependentRowCleanup(any(EntityManager.class), anyString(), any());
        return activator;
    }

    private void deleteThroughParticipant(RecorderRef recorder, RlsFilterActivator activator,
                                          boolean failAfterCleanup) {
        new TransactionTemplate(transactionManager).execute(status -> {
            EntityManager entityManager = EntityManagerFactoryUtils
                .getTransactionalEntityManager(entityManagerFactory);
            MetadataResolver metadataResolver = new MetadataResolver();
            SectionMetadataRegistry sections = new SectionMetadataRegistry(
                "org.ipro.registerfixtures", metadataResolver);
            sections.afterPropertiesSet();
            RlsPolicyEnforcer sectionPolicy = mock(RlsPolicyEnforcer.class);
            when(sectionPolicy.prepareRead(eq(RecorderFixtureDocumentA.class), any(EntityManager.class)))
                .thenReturn(true);
            GenericOwnedSectionService ownedSections = new GenericOwnedSectionService(
                validatorFactory.getValidator(), metadataResolver, sections, sectionPolicy);
            ReflectionTestUtils.setField(ownedSections, "entityManager", entityManager);

            var catalog = new org.ipro.data.EntityDescriptorCatalog(
                new ManagedEntityCatalog(entityManagerFactory), sections, metadataResolver,
                List.of(), List.of());
            var readExecutor = mock(org.ipro.data.CanonicalReadExecutor.class);
            doAnswer(invocation -> {
                org.ipro.data.DetailRead<?> request = invocation.getArgument(0);
                return java.util.Optional.ofNullable(entityManager.find(request.type(), request.id()));
            }).when(readExecutor).readDetail(any());
            var referenceCheck = mock(org.ipro.crud.ReferenceCheckService.class);
            if (failAfterCleanup) {
                doThrow(new IllegalStateException("REFERENCE_CHECK_FAILED"))
                    .when(referenceCheck).checkNoReferences(RecorderFixtureDocumentA.class, recorder.getId());
            }
            var deleteParticipant = new RecorderSetDeleteParticipant(entityManager, registerRegistry,
                recorderTypeRegistry, validatorFactory.getValidator(), activator);
            var canonicalWriter = new org.ipro.data.CanonicalWriteExecutor(catalog, readExecutor,
                entityManager, validatorFactory.getValidator(), null, null, null, null,
                ownedSections, referenceCheck, sections, org.ipro.data.WriteTelemetry.noop(),
                List.of(deleteParticipant), () -> "test-user",
                org.mockito.Mockito.mock(org.ipro.rls.c5.C5PermissionDecisionService.class));
            canonicalWriter.delete(RecorderFixtureDocumentA.class, recorder.getId());
            return null;
        });
    }

    private boolean documentExists(Class<?> type, Long id) {
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            return entityManager.find(type, id) != null;
        }
    }

    private long clockOrder(Class<?> registerType) {
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            return entityManager.find(RecorderOrderClock.class, RecorderOrderClock.keyFor(registerType)).getLastOrder();
        }
    }

    private long documentVersion(Class<?> type, Long id) {
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            return ((org.ipro.crud.BaseEntity) entityManager.find(type, id)).getVersion();
        }
    }

    private RecorderFixtureDocumentA persistDocumentA() {
        RecorderFixtureDocumentA document = new RecorderFixtureDocumentA();
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            var transaction = entityManager.getTransaction();
            transaction.begin();
            entityManager.persist(document);
            transaction.commit();
        }
        return document;
    }

    private RecorderFixtureDocumentB persistDocumentB() {
        RecorderFixtureDocumentB document = new RecorderFixtureDocumentB();
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            var transaction = entityManager.getTransaction();
            transaction.begin();
            entityManager.persist(document);
            transaction.commit();
        }
        return document;
    }

    private void persist(Object entity, boolean recorderWrite) {
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            var transaction = entityManager.getTransaction();
            transaction.begin();
            if (recorderWrite) {
                withRecorderScope(entity, () -> {
                    entityManager.persist(entity);
                    entityManager.flush();
                });
                transaction.commit();
            } else {
                entityManager.persist(entity);
                entityManager.flush();
                transaction.commit();
            }
        }
    }

    private void expectUniqueConstraint(Object entity, boolean recorderWrite) {
        Throwable failure = null;
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            var transaction = entityManager.getTransaction();
            transaction.begin();
            try {
                if (recorderWrite) {
                    withRecorderScope(entity, () -> {
                        entityManager.persist(entity);
                        entityManager.flush();
                    });
                    transaction.commit();
                } else {
                    entityManager.persist(entity);
                    entityManager.flush();
                    transaction.commit();
                }
            } catch (RuntimeException exception) {
                failure = exception;
                if (transaction.isActive()) transaction.rollback();
            }
        }
        assertDatabaseConstraintFailure(failure);
    }

    private void expectGuardFailure(Consumer<EntityManager> operation) {
        Throwable failure = null;
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            var transaction = entityManager.getTransaction();
            transaction.begin();
            try {
                operation.accept(entityManager);
                transaction.commit();
            } catch (RuntimeException exception) {
                failure = exception;
                if (transaction.isActive()) transaction.rollback();
            }
        }
        assertThat(exceptionMessages(failure)).contains("RECORDER_SET_WRITE_REQUIRED");
    }

    private static void withRecorderScope(Object entity, Runnable operation) {
        RecordedRegisterEntry row = (RecordedRegisterEntry) entity;
        try (RecorderSetWriteContext.Scope ignored = RecorderSetWriteContext.open(entity.getClass(), row.getRecorder())) {
            operation.run();
        }
    }

    private static void assertDatabaseConstraintFailure(Throwable failure) {
        assertThat(failure).isNotNull();
        String messages = exceptionMessages(failure).toLowerCase();
        assertThat(messages).containsAnyOf("unique", "constraint", "duplicate", "integrity");
    }

    private static String exceptionMessages(Throwable failure) {
        if (failure == null) return "";
        List<String> messages = new ArrayList<>();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            messages.add(current.getClass().getName() + ": " + current.getMessage());
        }
        return String.join(" | ", messages);
    }

    private static RecorderRef ref(RecorderFixtureDocumentA document) {
        return new RecorderRef("fixture-document-a", document.getId());
    }

    private static RecorderRef ref(RecorderFixtureDocumentB document) {
        return new RecorderRef("fixture-document-b", document.getId());
    }

    private static RecordedPeriodicRegisterFixture recordedPeriodic(String recorderType, Long recorderId,
                                                                    int line, long order,
                                                                    LocalDateTime period, Integer amount) {
        return new RecordedPeriodicRegisterFixture("item-a", new RecorderRef(recorderType, recorderId),
            line, order, period, amount);
    }
}
