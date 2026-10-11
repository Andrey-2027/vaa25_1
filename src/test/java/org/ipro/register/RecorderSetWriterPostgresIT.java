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
                validatorFactory.getValidator(), 1_000);
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
