package org.ipro.register;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.ipro.crud.BaseEntity;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.MetadataResolver;
import org.ipro.registerfixtures.RecorderPersistenceFixtures.RecorderFixtureDocumentA;
import org.ipro.registerfixtures.RecorderPersistenceFixtures.RecorderFixtureDocumentB;
import org.ipro.registerfixtures.RecorderPersistenceFixtures.RecordedPeriodicRegisterFixture;
import org.ipro.register.persistence.RecorderOrderClock;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import org.ipro.lifecycle.EntityDeleteContext;
import org.ipro.lifecycle.EntityLifecycle;
import org.ipro.lifecycle.EntityLifecycleRegistry;
import org.ipro.lifecycle.EntitySaveContext;
import org.ipro.lifecycle.EntityUpdateContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real PostgreSQL lock/version/rollback checks. The JDBC URL must name a disposable g3_register_test_* database. */
class RecorderSetWriterPostgresIT {
    private static final LocalDateTime PERIOD = LocalDateTime.of(2026, 10, 5, 9, 30);

    private StandardServiceRegistry serviceRegistry;
    private EntityManagerFactory entityManagerFactory;
    private ValidatorFactory validatorFactory;
    private RecorderSetWriter writer;
    private RecorderSetTransactionExecutor transactionExecutor;
    private RecorderTypeRegistry recorderTypeRegistry;
    private RegisterRegistry registerRegistry;

    @BeforeEach
    void connectToDisposablePostgresDatabase() {
        String url = System.getenv("IPRO_REGISTER_G3_JDBC_URL");
        Assumptions.assumeTrue(url != null && url.matches(
            "^jdbc:postgresql://(?:localhost|127\\.0\\.0\\.1)(?::[0-9]+)?/g3_register_test_[a-z0-9_]+$"),
            "Set IPRO_REGISTER_G3_JDBC_URL to a disposable local g3_register_test_* database");
        String username = System.getenv("IPRO_REGISTER_G3_DB_USER");
        String password = System.getenv("IPRO_REGISTER_G3_DB_PASSWORD");
        if (username == null || username.isBlank() || password == null) {
            throw new IllegalStateException("PostgreSQL test credentials are required when the test URL is set");
        }

        serviceRegistry = new StandardServiceRegistryBuilder()
            .applySetting("hibernate.connection.driver_class", "org.postgresql.Driver")
            .applySetting("hibernate.connection.url", url)
            .applySetting("hibernate.connection.username", username)
            .applySetting("hibernate.connection.password", password)
            .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
            .applySetting("hibernate.hbm2ddl.auto", "create-drop")
            .applySetting("hibernate.connection.pool_size", "8")
            .applySetting("hibernate.show_sql", "false")
            .build();
        try {
            entityManagerFactory = new MetadataSources(serviceRegistry)
                .addAnnotatedClass(RecorderFixtureDocumentA.class)
                .addAnnotatedClass(RecorderFixtureDocumentB.class)
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
                validatorFactory.getValidator(), new EntityLifecycleRegistry(List.of()), 1_000);
            PlatformTransactionManager manager = new JpaTransactionManager(entityManagerFactory);
            transactionExecutor = new RecorderSetTransactionExecutor(manager, 3, 20);
            new RecorderOrderClockBootstrap(entityManagerFactory, manager).initialize(registerRegistry.descriptors());
        } catch (RuntimeException | Error failure) {
            closeResources();
            throw failure;
        }
    }

    @AfterEach
    void closeDatabaseResources() {
        closeResources();
    }

    @Test
    void concurrentReplacementUsesDocumentVersionAndSerializedRegisterClock() throws Exception {
        RecorderFixtureDocumentA documentA = persistDocument(new RecorderFixtureDocumentA());
        RecorderFixtureDocumentB documentB = persistDocument(new RecorderFixtureDocumentB());
        assertThat(documentA.getId()).isEqualTo(documentB.getId());
        RecorderRef recorderA = new RecorderRef("fixture-document-a", documentA.getId());
        RecorderRef recorderB = new RecorderRef("fixture-document-b", documentB.getId());

        RecorderSetWriter.Result initial = replace(recorderA, documentA.getVersion(), List.of(
            row(recorderA, 1, 1L, 10)));
        assertThat(initial.documentVersion()).isEqualTo(1L);
        assertThat(initial.recordingOrder()).isEqualTo(1L);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        long raceStartedAt = System.nanoTime();
        Future<Attempt> one = pool.submit(() -> concurrentReplace(ready, start, recorderA, 1L, 20));
        Future<Attempt> two = pool.submit(() -> concurrentReplace(ready, start, recorderA, 1L, 30));
        try {
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            Attempt first = one.get(10, TimeUnit.SECONDS);
            Attempt second = two.get(10, TimeUnit.SECONDS);
            List<Attempt> attempts = List.of(first, second);
            assertThat(attempts.stream().filter(Attempt::succeeded).count()).isEqualTo(1L);
            Attempt conflict = attempts.stream().filter(attempt -> !attempt.succeeded()).findFirst().orElseThrow();
            assertThat(exceptionMessages(conflict.failure())).contains("RECORDER_VERSION_CONFLICT");
        } finally {
            pool.shutdownNow();
        }
        long raceMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - raceStartedAt);

        assertThat(clockOrder(RecordedPeriodicRegisterFixture.class)).isEqualTo(2L);
        assertThat(documentVersion(RecorderFixtureDocumentA.class, documentA.getId())).isEqualTo(2L);
        List<RecordedPeriodicRegisterFixture> rowsA = rows(recorderA);
        assertThat(rowsA).hasSize(1);
        assertThat(rowsA.getFirst().getRecordingOrder()).isEqualTo(2L);

        RecorderSetWriter.Result independentRecorder = replace(recorderB, documentB.getVersion(), List.of(
            row(recorderB, 1, 999L, 40)));
        assertThat(independentRecorder.recordingOrder()).isEqualTo(3L);
        assertThat(independentRecorder.documentVersion()).isEqualTo(1L);
        assertThat(rows(recorderB)).hasSize(1);

        RecorderSetWriter.Result empty = replace(recorderA, 2L, List.of());
        assertThat(empty.removedRows()).isEqualTo(1);
        assertThat(empty.insertedRows()).isZero();
        assertThat(empty.recordingOrder()).isEqualTo(4L);
        assertThat(rows(recorderA)).isEmpty();
        assertThat(rows(recorderB)).hasSize(1);
        System.getLogger(getClass().getName()).log(System.Logger.Level.INFO,
            "PostgreSQL recorder clock contention: two same-version replacements completed in {0} ms", raceMillis);
    }

    @Test
    void lockTimeoutRetryIsBoundedAndDatabaseFailureRollsBackTheWholeSet() {
        RecorderFixtureDocumentA document = persistDocument(new RecorderFixtureDocumentA());
        RecorderRef recorder = new RecorderRef("fixture-document-a", document.getId());
        replace(recorder, document.getVersion(), List.of(row(recorder, 1, 1L, 10)));

        EntityManager holder = entityManagerFactory.createEntityManager();
        var heldTransaction = holder.getTransaction();
        heldTransaction.begin();
        try {
            holder.find(RecorderFixtureDocumentA.class, document.getId(), LockModeType.PESSIMISTIC_WRITE);
            AtomicInteger attemptCount = new AtomicInteger();
            long timeoutStartedAt = System.nanoTime();
            assertThatThrownBy(() -> transactionExecutor.execute(() -> {
                attemptCount.incrementAndGet();
                return replace(recorder, 1L, List.of(row(recorder, 1, 2L, 20)));
            })).satisfies(failure -> assertThat(exceptionMessages(failure).toLowerCase())
                .containsAnyOf("locktimeout", "55p03", "pessimisticlock", "could not obtain lock"));
            long timeoutMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - timeoutStartedAt);
            assertThat(attemptCount).hasValue(3);
            assertThat(timeoutMillis).isLessThan(5_000L);
        } finally {
            if (heldTransaction.isActive()) heldTransaction.rollback();
            holder.close();
        }
        assertThat(clockOrder(RecordedPeriodicRegisterFixture.class)).isEqualTo(1L);
        assertThat(rows(recorder)).extracting(RecordedPeriodicRegisterFixture::getAmount).containsExactly(10);
        assertThat(documentVersion(RecorderFixtureDocumentA.class, document.getId())).isEqualTo(1L);

        assertThatThrownBy(() -> replace(recorder, 1L, List.of(
            row(recorder, 1, 3L, 30), row(recorder, 2, 4L, -1))))
            .isInstanceOf(RuntimeException.class);
        assertThat(rows(recorder)).extracting(RecordedPeriodicRegisterFixture::getAmount).containsExactly(10);
        assertThat(clockOrder(RecordedPeriodicRegisterFixture.class)).isEqualTo(1L);
        assertThat(documentVersion(RecorderFixtureDocumentA.class, document.getId())).isEqualTo(1L);

        assertThatThrownBy(() -> replace(recorder, 1L, List.of(
            row(recorder, 1, 5L, null))))
            .isInstanceOf(org.ipro.crud.ValidationException.class)
            .hasMessageContaining("RECORDER_SET_INVALID");
        assertThat(rows(recorder)).extracting(RecordedPeriodicRegisterFixture::getAmount).containsExactly(10);
        assertThat(clockOrder(RecordedPeriodicRegisterFixture.class)).isEqualTo(1L);
    }

    @Test
    void deleteWinningRecorderLockMakesWaitingReplacementFailWithoutOrphaningRows() throws Exception {
        RecorderFixtureDocumentA document = persistDocument(new RecorderFixtureDocumentA());
        RecorderRef recorder = new RecorderRef("fixture-document-a", document.getId());
        replace(recorder, 0L, List.of(row(recorder, 1, 1L, 10)));

        CountDownLatch deleteLocked = new CountDownLatch(1);
        CountDownLatch releaseDelete = new CountDownLatch(1);
        CountDownLatch replaceStarted = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> delete = pool.submit(() -> {
                try {
                    transactionExecutor.execute(() -> {
                        EntityManager entityManager = EntityManagerFactoryUtils
                            .getTransactionalEntityManager(entityManagerFactory);
                        BaseEntity root = lockRecorder(entityManager, recorder);
                        writer.deleteForRecorder(entityManager, RecordedPeriodicRegisterFixture.class, recorder);
                        deleteLocked.countDown();
                        await(releaseDelete);
                        entityManager.remove(root);
                        entityManager.flush();
                        return null;
                    });
                    return null;
                } catch (RuntimeException failure) {
                    return exceptionMessages(failure);
                }
            });
            assertThat(deleteLocked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<String> replace = pool.submit(() -> {
                replaceStarted.countDown();
                try {
                    replace(recorder, 1L, List.of(row(recorder, 1, 2L, 20)));
                    return null;
                } catch (RuntimeException failure) {
                    return exceptionMessages(failure);
                }
            });
            assertThat(replaceStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);
            releaseDelete.countDown();

            assertThat(delete.get(10, TimeUnit.SECONDS)).isNull();
            assertThat(replace.get(10, TimeUnit.SECONDS)).contains("RECORDER_DOCUMENT_NOT_FOUND");
        } finally {
            releaseDelete.countDown();
            pool.shutdownNow();
        }
        assertThat(rows(recorder)).isEmpty();
        assertThat(documentExists(RecorderFixtureDocumentA.class, document.getId())).isFalse();
    }

    @Test
    void replacementWinningRecorderLockMakesWaitingDeleteFailStaleVersionCheck() throws Exception {
        RecorderFixtureDocumentA document = persistDocument(new RecorderFixtureDocumentA());
        RecorderRef recorder = new RecorderRef("fixture-document-a", document.getId());
        CountDownLatch replacementLocked = new CountDownLatch(1);
        CountDownLatch releaseReplacement = new CountDownLatch(1);
        CountDownLatch deleteStarted = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> replacement = pool.submit(() -> {
                try {
                    transactionExecutor.execute(() -> {
                        EntityManager entityManager = EntityManagerFactoryUtils
                            .getTransactionalEntityManager(entityManagerFactory);
                        entityManager.find(RecorderFixtureDocumentA.class, document.getId(),
                            LockModeType.PESSIMISTIC_WRITE);
                        replacementLocked.countDown();
                        await(releaseReplacement);
                        writer.replaceForRecorder(entityManager, RecordedPeriodicRegisterFixture.class,
                            recorder, 0L, List.of(row(recorder, 1, 1L, 10)));
                        return null;
                    });
                    return null;
                } catch (RuntimeException failure) {
                    return exceptionMessages(failure);
                }
            });
            assertThat(replacementLocked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<String> delete = pool.submit(() -> {
                deleteStarted.countDown();
                try {
                    deleteRootWithExpectedVersion(recorder, 0L);
                    return null;
                } catch (RuntimeException failure) {
                    return exceptionMessages(failure);
                }
            });
            assertThat(deleteStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);
            releaseReplacement.countDown();

            assertThat(replacement.get(10, TimeUnit.SECONDS)).isNull();
            assertThat(delete.get(10, TimeUnit.SECONDS)).contains("RECORDER_DELETE_VERSION_CONFLICT");
        } finally {
            releaseReplacement.countDown();
            pool.shutdownNow();
        }
        assertThat(rows(recorder)).extracting(RecordedPeriodicRegisterFixture::getAmount).containsExactly(10);
        assertThat(documentVersion(RecorderFixtureDocumentA.class, document.getId())).isEqualTo(1L);
    }

    @Test
    void referenceCheckFailureAfterRecorderCleanupRollsBackTheWholeDocumentDelete() {
        RecorderFixtureDocumentA document = persistDocument(new RecorderFixtureDocumentA());
        RecorderRef recorder = new RecorderRef("fixture-document-a", document.getId());
        replace(recorder, 0L, List.of(row(recorder, 1, 1L, 10)));

        assertThatThrownBy(() -> transactionExecutor.execute(() -> {
            EntityManager entityManager = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
            BaseEntity root = lockRecorder(entityManager, recorder);
            writer.deleteForRecorder(entityManager, RecordedPeriodicRegisterFixture.class, recorder);
            throw new IllegalStateException("REFERENCE_CHECK_FAILED");
        })).hasMessageContaining("REFERENCE_CHECK_FAILED");

        assertThat(rows(recorder)).extracting(RecordedPeriodicRegisterFixture::getAmount).containsExactly(10);
        assertThat(documentVersion(RecorderFixtureDocumentA.class, document.getId())).isEqualTo(1L);
    }

    @Test
    void documentEditUsingVersionBeforeReplacementIsRejected() {
        RecorderFixtureDocumentA document = persistDocument(new RecorderFixtureDocumentA());
        RecorderRef recorder = new RecorderRef("fixture-document-a", document.getId());
        replace(recorder, 0L, List.of(row(recorder, 1, 1L, 10)));

        RecorderFixtureDocumentA stale = new RecorderFixtureDocumentA();
        stale.setId(document.getId());
        stale.setVersion(0L);
        stale.setNote("edited after replacement");
        assertThatThrownBy(() -> transactionExecutor.execute(() -> {
            EntityManager entityManager = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
            entityManager.merge(stale);
            entityManager.flush();
            return null;
        })).isInstanceOf(OptimisticLockException.class);

        assertThat(documentVersion(RecorderFixtureDocumentA.class, document.getId())).isEqualTo(1L);
        assertThat(rows(recorder)).extracting(RecordedPeriodicRegisterFixture::getAmount).containsExactly(10);
    }

    /**
     * ADR-0025 на реальном PostgreSQL: счётчик хуков строки на пути набора.
     *
     * <p>Здесь проверяется то, чего H2 не показывает: порядок «подготовка строки → порядок записи под
     * замком → хук → валидация» на настоящем flush, и откат уже выделенного порядка записи вместе с
     * вето хука. Счётчик — по факту вызова на каждый экземпляр строки, поэтому «ровно один раз на
     * вставленную строку» отличается от «хук вообще был».</p>
     */
    @Test
    void rowLifecycleHooksRunOncePerInsertedRowAndVetoRollsBackOrderAndVersion() {
        RecorderFixtureDocumentA document = persistDocument(new RecorderFixtureDocumentA());
        RecorderRef recorder = new RecorderRef("fixture-document-a", document.getId());
        RowHookCounter hooks = new RowHookCounter();
        RecorderSetWriter hookAware = new RecorderSetWriter(registerRegistry, recorderTypeRegistry,
            validatorFactory.getValidator(), new EntityLifecycleRegistry(List.of(hooks)), 1_000);

        RecorderSetWriter.Result initial = replaceWith(hookAware, recorder, 0L, List.of(
            row(recorder, 1, 1L, 10), row(recorder, 2, 1L, 11)));
        assertThat(hooks.beforeSave).as("две новые строки — ровно два beforeSave")
            .hasSize(2).doesNotHaveDuplicates();
        assertThat(hooks.saved).as("две новые строки — ровно два onSave")
            .hasSize(2).doesNotHaveDuplicates();
        assertThat(hooks.saved).extracting(RecordedPeriodicRegisterFixture::getId).doesNotContainNull();
        assertThat(hooks.deleted).isEmpty();
        assertThat(hooks.updated).as("beforeUpdate на этом пути не вызывается").isEmpty();
        assertThat(hooks.linesAtBeforeSave).containsExactly(1, 2);
        assertThat(hooks.ordersAtBeforeSave).as("хук видит уже присвоенный порядок записи")
            .containsOnly(1L);
        List<Long> firstIds = rows(recorder).stream()
            .map(RecordedPeriodicRegisterFixture::getId).toList();

        hooks.reset();
        RecorderSetWriter.Result second = replaceWith(hookAware, recorder, initial.documentVersion(),
            List.of(row(recorder, 1, 1L, 20), row(recorder, 2, 1L, 21), row(recorder, 3, 1L, 22)));
        assertThat(hooks.beforeSave).as("замена трёх строк — три хука, ни одного лишнего")
            .hasSize(3).doesNotHaveDuplicates();
        assertThat(hooks.saved).hasSize(3).doesNotHaveDuplicates();
        assertThat(hooks.deleted).as("два снятых ряда — два удаления-хука")
            .hasSize(2).doesNotHaveDuplicates();
        assertThat(hooks.deleted).extracting(RecordedPeriodicRegisterFixture::getId)
            .containsExactlyInAnyOrderElementsOf(firstIds);
        assertThat(hooks.saved).extracting(RecordedPeriodicRegisterFixture::getId)
            .doesNotContainAnyElementsOf(firstIds);
        assertThat(hooks.updated).isEmpty();
        assertThat(rows(recorder)).hasSize(3);

        hooks.reset();
        int appendedLine = transactionExecutor.execute(() -> {
            EntityManager entityManager = EntityManagerFactoryUtils
                .getTransactionalEntityManager(entityManagerFactory);
            var managed = entityManager.find(RecorderFixtureDocumentA.class, document.getId());
            List<RecordedPeriodicRegisterFixture> added = hookAware.addForRecorder(entityManager,
                RecordedPeriodicRegisterFixture.class, recorder, managed, List.of(row(recorder, 4, 1L, 23)));
            return added.getFirst().getLineNumber();
        });
        assertThat(hooks.beforeSave).as("добавление не даёт хуков существующим строкам набора")
            .hasSize(1).doesNotHaveDuplicates();
        assertThat(hooks.saved).hasSize(1).doesNotHaveDuplicates();
        assertThat(hooks.deleted).isEmpty();
        assertThat(appendedLine).as("номер строки выдан до хука и продолжает набор").isEqualTo(4);
        assertThat(hooks.linesAtBeforeSave).containsExactly(4);
        assertThat(hooks.saved.getFirst().getAmount()).as("onSave видит вставленную строку её значением")
            .isEqualTo(23);
        assertThat(hooks.contexts).extracting(org.ipro.events.EventContext::operationName)
            .allSatisfy(name -> assertThat(name).startsWith("set-register-recorder-set:"));

        Long versionBeforeVeto = documentVersion(RecorderFixtureDocumentA.class, document.getId());
        long orderBeforeVeto = clockOrder(RecordedPeriodicRegisterFixture.class);
        List<Integer> amountsBeforeVeto = amounts(recorder);
        assertThat(second.documentVersion()).isEqualTo(versionBeforeVeto);
        hooks.reset();
        hooks.veto = new org.ipro.crud.ValidationException("Строка регистра отклонена предметным правилом");
        assertThatThrownBy(() -> replaceWith(hookAware, recorder, versionBeforeVeto,
                List.of(row(recorder, 1, 1L, 30))))
            .as("вето хука откатывает набор целиком, включая выделенный порядок записи")
            .satisfies(failure -> assertThat(exceptionMessages(failure))
                .contains("Строка регистра отклонена предметным правилом"));
        assertThat(hooks.beforeSave).hasSize(1);
        assertThat(hooks.saved).as("откат не оставляет ни одной частично записанной строки").isEmpty();
        assertThat(amounts(recorder)).isEqualTo(amountsBeforeVeto);
        assertThat(documentVersion(RecorderFixtureDocumentA.class, document.getId()))
            .isEqualTo(versionBeforeVeto);
        assertThat(clockOrder(RecordedPeriodicRegisterFixture.class))
            .as("порядок записи, выделенный откаченной операцией, не остаётся занятым")
            .isEqualTo(orderBeforeVeto);
    }

    /** Счётчик вызовов хука: фиксирует каждый вызов отдельно, поэтому различает «один раз» и «вообще». */
    private static final class RowHookCounter implements EntityLifecycle<RecordedPeriodicRegisterFixture> {

        private final List<RecordedPeriodicRegisterFixture> beforeSave = new java.util.ArrayList<>();
        private final List<RecordedPeriodicRegisterFixture> saved = new java.util.ArrayList<>();
        private final List<RecordedPeriodicRegisterFixture> deleted = new java.util.ArrayList<>();
        private final List<RecordedPeriodicRegisterFixture> updated = new java.util.ArrayList<>();
        private final List<org.ipro.events.EventContext> contexts = new java.util.ArrayList<>();
        private final List<Integer> linesAtBeforeSave = new java.util.ArrayList<>();
        private final List<Long> ordersAtBeforeSave = new java.util.ArrayList<>();
        private RuntimeException veto;

        @Override public Class<RecordedPeriodicRegisterFixture> entityType() {
            return RecordedPeriodicRegisterFixture.class;
        }

        @Override public void beforeSave(EntitySaveContext<RecordedPeriodicRegisterFixture> context) {
            beforeSave.add(context.entity());
            contexts.add(context.eventContext());
            linesAtBeforeSave.add(context.entity().getLineNumber());
            ordersAtBeforeSave.add(context.entity().getRecordingOrder());
            if (veto != null) throw veto;
        }

        @Override public void beforeUpdate(EntityUpdateContext<RecordedPeriodicRegisterFixture> context) {
            updated.add(context.updated());
        }

        @Override public void onSave(EntitySaveContext<RecordedPeriodicRegisterFixture> context) {
            saved.add(context.entity());
            contexts.add(context.eventContext());
        }

        @Override public void beforeDelete(EntityDeleteContext<RecordedPeriodicRegisterFixture> context) {
            deleted.add(context.entity());
            contexts.add(context.eventContext());
        }

        private void reset() {
            beforeSave.clear();
            saved.clear();
            deleted.clear();
            updated.clear();
            contexts.clear();
            linesAtBeforeSave.clear();
            ordersAtBeforeSave.clear();
        }
    }

    private RecorderSetWriter.Result replaceWith(RecorderSetWriter target, RecorderRef recorder,
                                                 Long expectedVersion,
                                                 List<RecordedPeriodicRegisterFixture> proposedRows) {
        return transactionExecutor.execute(() -> {
            EntityManager entityManager = EntityManagerFactoryUtils
                .getTransactionalEntityManager(entityManagerFactory);
            return target.replaceForRecorder(entityManager, RecordedPeriodicRegisterFixture.class,
                recorder, expectedVersion, proposedRows);
        });
    }

    private List<Integer> amounts(RecorderRef recorder) {
        return rows(recorder).stream().map(RecordedPeriodicRegisterFixture::getAmount).toList();
    }

    private Attempt concurrentReplace(CountDownLatch ready, CountDownLatch start,
                                      RecorderRef recorder, Long expectedVersion, int amount) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("race start timed out");
            return new Attempt(true, replace(recorder, expectedVersion, List.of(row(recorder, 1, amount, amount))));
        } catch (RuntimeException failure) {
            return new Attempt(false, failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Attempt(false, interrupted);
        }
    }

    private RecorderSetWriter.Result replace(RecorderRef recorder, Long expectedVersion,
                                              List<RecordedPeriodicRegisterFixture> proposedRows) {
        return transactionExecutor.execute(() -> {
            EntityManager entityManager = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
            return writer.replaceForRecorder(entityManager, RecordedPeriodicRegisterFixture.class,
                recorder, expectedVersion, proposedRows);
        });
    }

    private <T extends BaseEntity> T persistDocument(T document) {
        return transactionExecutor.execute(() -> {
            EntityManager entityManager = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
            entityManager.persist(document);
            entityManager.flush();
            return document;
        });
    }

    private BaseEntity lockRecorder(EntityManager entityManager, RecorderRef recorder) {
        Class<?> type = recorderTypeRegistry.resolveType(recorder);
        Object value = entityManager.find(type, recorder.getId(), LockModeType.PESSIMISTIC_WRITE,
            java.util.Map.of("jakarta.persistence.lock.timeout", 1_000));
        if (!(value instanceof BaseEntity base)) throw new jakarta.persistence.EntityNotFoundException(
            "RECORDER_DOCUMENT_NOT_FOUND");
        return base;
    }

    private void deleteRootWithExpectedVersion(RecorderRef recorder, Long expectedVersion) {
        transactionExecutor.execute(() -> {
            EntityManager entityManager = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
            Class<?> type = recorderTypeRegistry.resolveType(recorder);
            Object value = entityManager.find(type, recorder.getId(), LockModeType.PESSIMISTIC_WRITE,
                java.util.Map.of("jakarta.persistence.lock.timeout", 1_000));
            if (!(value instanceof BaseEntity root)) throw new jakarta.persistence.EntityNotFoundException(
                "RECORDER_DOCUMENT_NOT_FOUND");
            entityManager.refresh(root, LockModeType.PESSIMISTIC_WRITE,
                java.util.Map.of("jakarta.persistence.lock.timeout", 1_000));
            Long actualVersion = root.getVersion() == null ? 0L : root.getVersion();
            if (!expectedVersion.equals(actualVersion)) {
                throw new OptimisticLockException("RECORDER_DELETE_VERSION_CONFLICT: expected="
                    + expectedVersion + ", actual=" + actualVersion);
            }
            writer.deleteForRecorder(entityManager, RecordedPeriodicRegisterFixture.class, recorder);
            entityManager.remove(root);
            entityManager.flush();
            return null;
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted", interrupted);
        }
    }

    private List<RecordedPeriodicRegisterFixture> rows(RecorderRef recorder) {
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

    private long clockOrder(Class<?> registerType) {
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            return entityManager.find(RecorderOrderClock.class, RecorderOrderClock.keyFor(registerType)).getLastOrder();
        }
    }

    private long documentVersion(Class<?> type, Long id) {
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            return ((BaseEntity) entityManager.find(type, id)).getVersion();
        }
    }

    private boolean documentExists(Class<?> type, Long id) {
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            return entityManager.find(type, id) != null;
        }
    }

    private static RecordedPeriodicRegisterFixture row(RecorderRef recorder, int inputLine,
                                                        long inputOrder, Integer amount) {
        return new RecordedPeriodicRegisterFixture("item-shared", recorder, inputLine,
            inputOrder, PERIOD, amount);
    }

    private static String exceptionMessages(Throwable failure) {
        List<String> messages = new ArrayList<>();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            messages.add(current.getClass().getName() + ": " + current.getMessage());
        }
        return String.join(" | ", messages);
    }

    private void closeResources() {
        if (validatorFactory != null) validatorFactory.close();
        if (entityManagerFactory != null) entityManagerFactory.close();
        if (serviceRegistry != null) StandardServiceRegistryBuilder.destroy(serviceRegistry);
    }

    private record Attempt(boolean succeeded, Object result, Throwable failure) {
        Attempt(boolean succeeded, RecorderSetWriter.Result result) { this(succeeded, result, null); }
        Attempt(boolean succeeded, Throwable failure) { this(succeeded, null, failure); }
    }
}
