package org.ipro.register;

import jakarta.persistence.*;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.ipro.crud.*;
import org.ipro.data.EntityDataAccess;
import org.ipro.events.EntityEventPublisher;
import org.ipro.events.EventSource;
import org.ipro.lifecycle.*;
import org.ipro.metadata.*;
import org.ipro.metadata.annotation.EntityKind;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.register.persistence.RecorderOrderClock;
import org.ipro.rls.*;
import org.junit.jupiter.api.*;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real PostgreSQL transactions; authorization is covered separately by the application pilot. */
class RegisterWriteConcurrencyPostgresIT {
    private StandardServiceRegistry registry;
    private EntityManagerFactory factory;
    private EntityManager em;
    private JpaTransactionManager manager;
    private ValidatorFactory validation;
    private RegisterRegistry registers;
    private RecorderTypeRegistry types;
    private RecorderSetWriter writer;
    private Long owner;
    private RecorderRef recorder;
    private Long rowId;
    private final LocalDateTime period = LocalDateTime.of(2026, 10, 9, 12, 0);

    @BeforeEach void connect() {
        String url = System.getenv("IPRO_REGISTER_G3_JDBC_URL");
        Assumptions.assumeTrue(url != null && url.matches("^jdbc:postgresql://(?:localhost|127\\.0\\.0\\.1)(?::[0-9]+)?/g3_register_test_[a-z0-9_]+$"),
            "Requires a disposable local g3_register_test_* database");
        String user = Objects.requireNonNull(System.getenv("IPRO_REGISTER_G3_DB_USER"));
        String password = Objects.requireNonNull(System.getenv("IPRO_REGISTER_G3_DB_PASSWORD"));
        registry = new StandardServiceRegistryBuilder()
            .applySetting("hibernate.connection.driver_class", "org.postgresql.Driver")
            .applySetting("hibernate.connection.url", url).applySetting("hibernate.connection.username", user)
            .applySetting("hibernate.connection.password", password).applySetting("hibernate.connection.pool_size", 8)
            .applySetting("hibernate.hbm2ddl.auto", "create-drop").build();
        factory = new MetadataSources(registry).addAnnotatedClass(Owner.class).addAnnotatedClass(Row.class)
            .addAnnotatedClass(RegisterKeyChangesTest.KeyRow.class).addAnnotatedClass(RecorderOrderClock.class)
            .buildMetadata().buildSessionFactory();
        manager = new JpaTransactionManager(factory);
        em = SharedEntityManagerCreator.createSharedEntityManager(factory);
        validation = Validation.buildDefaultValidatorFactory();
        var catalog = new ManagedEntityCatalog(factory);
        types = new RecorderTypeRegistry(catalog, factory, new MetadataResolver());
        registers = new RegisterRegistry(catalog, types); registers.initialize();
        new RecorderOrderClockBootstrap(factory, manager).initialize(registers.descriptors());
        new RegisterKeyWriteGuardInstaller(factory).afterPropertiesSet();
        writer = new RecorderSetWriter(registers, types, validation.getValidator(),
            new EntityLifecycleRegistry(List.of()), 1000);
        owner = tx(() -> { var doc = new Owner(); em.persist(doc); em.flush(); return doc.getId(); });
        recorder = new RecorderRef("r8-concurrency-owner", owner);
        tx(() -> writer.replaceForRecorder(em, Row.class, recorder, 0L, List.of(row(10))));
        rowId = rows().getFirst().getId();
    }
    @AfterEach void close() {
        if (validation != null) validation.close();
        if (factory != null) factory.close();
        if (registry != null) StandardServiceRegistryBuilder.destroy(registry);
    }
    private <T> T tx(Callable<T> action) {
        return new TransactionTemplate(manager).execute(status -> {
            try { return action.call(); } catch (RuntimeException failure) { throw failure; }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        });
    }
    private RegisterKeyChanges keys(Runnable locked) {
        EntityDataAccess reads = mock(EntityDataAccess.class);
        when(reads.detail(any(), any())).thenAnswer(call -> Optional.ofNullable(em.find(call.getArgument(0), call.getArgument(1))));
        EntityLifecycle<Row> lifecycle = new EntityLifecycle<>() {
            public Class<Row> entityType() { return Row.class; }
            public void beforeSave(EntitySaveContext<Row> context) { locked.run(); }
        };
        return new RegisterKeyChanges(em, registers, mock(RegisterKeyChangePermissions.class), reads,
            mock(RlsPolicyEnforcer.class), validation.getValidator(), new EntityLifecycleRegistry(List.of(lifecycle)),
            new EntityEventPublisher(event -> { }), () -> "postgres-r8-test", Optional.empty(), manager);
    }
    private Row change(RegisterKeyChanges keys, String key) {
        return keys.change(Row.class, rowId, 0L, Map.of("itemKey", key), period.plusDays(1));
    }
    private List<Row> rows() { return tx(() -> em.createQuery("from R8ConcurrencyRow order by lineNumber", Row.class).getResultList()); }
    private Row row(int amount) { var value = new Row(); value.itemKey = "original"; value.amount = amount; value.setPeriod(period); value.setRecorder(recorder); return value; }

    @RepeatedTest(3) void twoKeyChangesWithSameVersionHaveOneWinner() throws Exception {
        ordered(pause -> change(keys(pause), "first"), () -> change(keys(() -> { }), "second"), true);
        assertThat(rows()).singleElement().satisfies(value -> {
            assertThat(value.getId()).isEqualTo(rowId); assertThat(value.itemKey).isEqualTo("first");
            assertThat(value.getVersion()).isEqualTo(1); assertThat(value.amount).isEqualTo(10);
        });
        assertThat(tx(() -> em.find(Owner.class, owner).getVersion())).isEqualTo(1);
    }
    @RepeatedTest(3) void keyChangeThenReplacementLeavesOneNewCompleteSet() throws Exception {
        ordered(pause -> change(keys(pause), "manual"),
            () -> tx(() -> writer.replaceForRecorder(em, Row.class, recorder, 1L, List.of(row(20)))), false);
        assertReplacement();
    }
    @RepeatedTest(3) void replacementThenKeyChangeRejectsTheRemovedId() throws Exception {
        ordered(pause -> tx(() -> { writer.replaceForRecorder(em, Row.class, recorder, 1L, List.of(row(20))); pause.run(); return null; }),
            () -> change(keys(() -> { }), "manual"), true);
        assertReplacement();
    }
    private void assertReplacement() {
        assertThat(rows()).singleElement().satisfies(value -> {
            assertThat(value.getId()).isNotEqualTo(rowId); assertThat(value.itemKey).isEqualTo("original");
            assertThat(value.amount).isEqualTo(20); assertThat(value.getLineNumber()).isEqualTo(1);
            assertThat(value.getRecordingOrder()).isEqualTo(2);
        });
    }
    @RepeatedTest(3) void keyChangeThenDeleteLeavesNoOrphans() throws Exception {
        ordered(pause -> change(keys(pause), "manual"), () -> delete(() -> { }), false);
        assertDeleted();
    }
    @RepeatedTest(3) void deleteThenKeyChangeRejectsTheRemovedId() throws Exception {
        ordered(pause -> delete(pause), () -> change(keys(() -> { }), "manual"), true);
        assertDeleted();
    }
    private Void delete(Runnable pause) {
        return tx(() -> {
            var doc = em.find(Owner.class, owner, LockModeType.PESSIMISTIC_WRITE);
            writer.deleteForRecorder(em, Row.class, recorder); em.remove(doc); em.flush(); pause.run(); return null;
        });
    }
    private void assertDeleted() { assertThat(rows()).isEmpty(); assertThat(tx(() -> em.find(Owner.class, owner))).isNull(); }

    @RepeatedTest(3) void concurrentAppendUsesFreshOwnerStateAndUniqueLineNumbers() throws Exception {
        ordered(pause -> append(20, pause), () -> append(30, () -> { }), false);
        assertThat(rows()).extracting(value -> value.amount).containsExactly(10, 20, 30);
        assertThat(rows()).extracting(Row::getLineNumber).containsExactly(1, 2, 3);
        assertThat(rows()).extracting(Row::getRecordingOrder).containsOnly(1L);
        assertThat(rows().getFirst().getId()).isEqualTo(rowId);
    }
    private Void append(int amount, Runnable pause) {
        return tx(() -> {
            // This is the owner save boundary: read the latest owner while holding its write lock.
            var doc = em.find(Owner.class, owner, LockModeType.PESSIMISTIC_WRITE);
            var dimensions = mock(RlsDimensionRegistry.class);
            when(dimensions.dimensions()).thenReturn(Set.of()); when(dimensions.filterableDimensionsByTable()).thenReturn(Map.of());
            var user = mock(RlsCurrentUser.class); when(user.requireAuthenticatedUsername()).thenReturn("postgres-r8-test");
            var api = new RegisterRecords(em, registers, types, validation.getValidator(),
                new EntityLifecycleRegistry(List.of()),
                new RlsFilterActivator(dimensions, null, user), request -> { }, manager);
            EntityLifecycle<Owner> lifecycle = new EntityLifecycle<>() {
                public Class<Owner> entityType() { return Owner.class; }
                public void onSave(EntitySaveContext<Owner> context) { api.forRecorder(context).add(Row.class, List.of(row(amount))); pause.run(); }
            };
            var services = mock(ServiceLocator.class); BaseService service = mock(BaseService.class);
            doReturn(service).when(services).findService(Owner.class); when(service.save(any())).thenAnswer(call -> call.getArgument(0));
            var events = mock(EntityEventPublisher.class); when(events.openAggregateOperation(any())).thenReturn(mock(EntityEventPublisher.EventScope.class));
            new MetadataDrivenAggregateSaveService(services, mock(SectionMetadataRegistry.class),
                mock(GenericOwnedSectionService.class), events, new EntityLifecycleRegistry(List.of(lifecycle)))
                .save(doc, List.of(), EventSource.SYSTEM);
            return null;
        });
    }
    @RepeatedTest(3) void independentRowsCompetingForOneUniqueKeyCannotOverwriteEachOther() throws Exception {
        Long first = standalone("one"), second = standalone("two");
        ordered(pause -> tx(() -> { var result = keys(() -> { }).change(RegisterKeyChangesTest.KeyRow.class, first, 0L, Map.of("code", "shared"), null); pause.run(); return result; }),
            () -> keys(() -> { }).change(RegisterKeyChangesTest.KeyRow.class, second, 0L, Map.of("code", "shared"), null), true);
        assertThat(tx(() -> em.find(RegisterKeyChangesTest.KeyRow.class, first).code)).isEqualTo("shared");
        assertThat(tx(() -> em.find(RegisterKeyChangesTest.KeyRow.class, second).code)).isEqualTo("two");
    }
    private Long standalone(String code) { return tx(() -> { var value = new RegisterKeyChangesTest.KeyRow(); value.code = code; value.amount = 42; em.persist(value); em.flush(); return value.getId(); }); }

    /** Observe a real PostgreSQL waiter before releasing the first transaction; no timing-only race claim. */
    private void ordered(Consumer<Runnable> first, Callable<?> second, boolean secondMustFail) throws Exception {
        CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(() -> first.accept(() -> { held.countDown(); await(release); }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> b = pool.submit(() -> { try { second.call(); return null; } catch (Exception failure) { return failure; } });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline && !b.isDone()) {
                waiting = tx(() -> ((Number) em.createNativeQuery("select count(*) from pg_stat_activity where datname=current_database() and cardinality(pg_blocking_pids(pid))>0", Long.class).getSingleResult()).longValue() > 0);
                if (!waiting) Thread.sleep(10);
            }
            assertThat(waiting).as("second transaction must actually wait for a PostgreSQL lock").isTrue();
            release.countDown(); a.get(15, TimeUnit.SECONDS);
            Throwable failure = b.get(15, TimeUnit.SECONDS);
            if (secondMustFail) {
                assertThat(failure).isNotNull();
                List<String> causes = new ArrayList<>();
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) causes.add(cause.getClass().getSimpleName());
                assertThat(causes).as("expected stale version, removed row or UNIQUE conflict; never a deadlock/timeout: %s", failure)
                    .anyMatch(name -> Set.of("OptimisticLockException", "ValidationException", "ConstraintViolationException").contains(name));
            } else assertThat(failure).isNull();
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue(); }
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(12, TimeUnit.SECONDS)) throw new IllegalStateException("test release timeout"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }
    @Entity(name="R8ConcurrencyOwner") @Table(name="r8_concurrency_owner")
    @EntityMetadata(kind=EntityKind.DOCUMENT) @RecorderType("r8-concurrency-owner")
    static class Owner extends BaseEntity { }
    @Entity(name="R8ConcurrencyRow") @Table(name="r8_concurrency_row", uniqueConstraints=@UniqueConstraint(columnNames={"recorder_type","recorder_id","line_no"}))
    @AttributeOverride(name="period", column=@Column(name="period_date",nullable=false,updatable=true))
    @RegisterMetadata(dimensions="itemKey",period="period",resources="amount",recorderTypes=Owner.class,keyChangeAllowed=true)
    static class Row extends PeriodicRecordedRegisterEntryBase {
        @Column(name="item_key",nullable=false) String itemKey;
        @Column(nullable=false) int amount;
    }
}
