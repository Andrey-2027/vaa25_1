package org.ipro.register;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.ipro.crud.BaseEntity;
import org.ipro.crud.ValidationException;
import org.ipro.data.EntityDataAccess;
import org.ipro.events.EntityChangedEvent;
import org.ipro.events.EntityEventPublisher;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.lifecycle.EntityDeleteContext;
import org.ipro.lifecycle.EntityLifecycle;
import org.ipro.lifecycle.EntityLifecycleRegistry;
import org.ipro.lifecycle.EntitySaveContext;
import org.ipro.lifecycle.EntityUpdateContext;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsPolicyEnforcer;
import org.ipro.rls.c5.C5OperationPermissionRegistry;
import org.ipro.rls.c5.C5PermissionDecisionService;
import org.ipro.telemetry.api.EventSink;
import org.ipro.telemetry.api.FieldChangeRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Запись регистра по ключу на H2: вердикты, границы декларации и прав, lifecycle, аудит.
 *
 * <p>Гонки и серверную семантику проверяет только PostgreSQL (ADR-0023); здесь фиксируется
 * контракт команды — что именно она делает со строкой, кого спрашивает и что публикует.</p>
 */
class RegisterKeyWritesTest {

    private static final LocalDateTime PERIOD = LocalDateTime.of(2026, 10, 12, 9, 0);

    private StandardServiceRegistry hibernate;
    private EntityManagerFactory factory;
    private EntityManager em;
    private JpaTransactionManager manager;
    private ValidatorFactory validator;
    private RegisterRegistry registers;
    private RegisterKeyWritePermissions permissions;
    private RlsPolicyEnforcer policies;
    private EventSink audit;
    private RegisterKeyWrites command;
    private final List<Object> published = new ArrayList<>();
    private final List<String> callbacks = new ArrayList<>();
    private final List<Boolean> distinctOriginals = new ArrayList<>();

    @BeforeEach
    void setUp() {
        hibernate = new StandardServiceRegistryBuilder()
            .applySetting("hibernate.connection.driver_class", "org.h2.Driver")
            .applySetting("hibernate.connection.url", "jdbc:h2:mem:keywrite_" + UUID.randomUUID())
            .applySetting("hibernate.hbm2ddl.auto", "create-drop")
            .applySetting("hibernate.show_sql", "false").build();
        factory = new MetadataSources(hibernate).addAnnotatedClass(PeriodRow.class)
            .addAnnotatedClass(ClosedRow.class).buildMetadata().buildSessionFactory();
        new RegisterKeyWriteGuardInstaller(factory).afterPropertiesSet();
        manager = new JpaTransactionManager(factory);
        em = SharedEntityManagerCreator.createSharedEntityManager(factory);
        validator = Validation.buildDefaultValidatorFactory();
        registers = mock(RegisterRegistry.class);
        when(registers.require(PeriodRow.class)).thenReturn(RegisterDescriptor.describe(PeriodRow.class));
        when(registers.find(PeriodRow.class)).thenReturn(Optional.of(RegisterDescriptor.describe(PeriodRow.class)));
        when(registers.require(ClosedRow.class)).thenReturn(RegisterDescriptor.describe(ClosedRow.class));
        when(registers.find(ClosedRow.class)).thenReturn(Optional.of(RegisterDescriptor.describe(ClosedRow.class)));
        permissions = mock(RegisterKeyWritePermissions.class);
        policies = mock(RlsPolicyEnforcer.class);
        audit = mock(EventSink.class);
        command = command(readingCanonically(), permissions, 3);
    }

    @AfterEach
    void close() {
        validator.close();
        factory.close();
        StandardServiceRegistryBuilder.destroy(hibernate);
    }

    /** Одна first-выборка идёт через реальный Criteria: команда ищет строку по полному ключу. */
    @SuppressWarnings("unchecked")
    private EntityDataAccess readingCanonically() {
        var access = mock(EntityDataAccess.class);
        when(access.first(eq(PeriodRow.class), any(), any(Sort.class))).thenAnswer(invocation ->
            query(invocation.getArgument(1), invocation.getArgument(2)));
        when(access.detail(any(), any())).thenAnswer(invocation ->
            Optional.ofNullable(em.find((Class<Object>) invocation.getArgument(0), invocation.getArgument(1))));
        return access;
    }

    private Optional<PeriodRow> query(Specification<PeriodRow> specification, Sort sort) {
        var builder = em.getCriteriaBuilder();
        var query = builder.createQuery(PeriodRow.class);
        var root = query.from(PeriodRow.class);
        query.select(root).where(specification.toPredicate(root, query, builder));
        query.orderBy(sort.stream().map(order -> order.isAscending()
            ? builder.asc(root.get(order.getProperty()))
            : builder.desc(root.get(order.getProperty()))).toList());
        return em.createQuery(query).setMaxResults(1).getResultList().stream().findFirst();
    }

    private RegisterKeyWrites command(EntityDataAccess reads, RegisterKeyWritePermissions writing,
            int maxAttempts) {
        return new RegisterKeyWrites(em, registers, writing, reads, policies, validator.getValidator(),
            new EntityLifecycleRegistry(List.of(new Recorder())), new EntityEventPublisher(published::add),
            () -> "key-write-test", Optional.of(audit), manager, maxAttempts);
    }

    @Test
    void setOnAnAbsentKeyCreatesTheRowWithItsResources() {
        RegisterKeyWrites.Result<PeriodRow> result = command.set(PeriodRow.class,
            Map.of("code", "sku-1"), Map.of("amount", new BigDecimal("10")), PERIOD);

        assertThat(result.verdict()).isEqualTo(RegisterKeyWrites.Verdict.CREATED);
        assertThat(result.written()).isTrue();
        assertThat(load("sku-1")).satisfies(row -> {
            assertThat(row.amount).isEqualByComparingTo("10");
            assertThat(row.getPeriod()).isEqualTo(PERIOD);
            assertThat(row.getVersion()).isZero();
        });
        assertThat(callbacks).containsExactly("beforeSave", "onSave");
        assertThat(published).anyMatch(EntityChangedEvent.class::isInstance);
        assertThat(auditChanges("CREATE")).singleElement().satisfies(payload ->
            assertThat(payload).contains("amount").contains("10"));
        verify(policies, atLeastOnce()).recordCanonicalWrite(any());
    }

    @Test
    void setOnAnExistingKeyReplacesResourcesWithoutMovingTheKey() {
        Long id = seed("sku-2", "10");

        RegisterKeyWrites.Result<PeriodRow> result = command.set(PeriodRow.class,
            Map.of("code", "sku-2"), Map.of("amount", new BigDecimal("12")), PERIOD);

        assertThat(result.verdict()).isEqualTo(RegisterKeyWrites.Verdict.UPDATED);
        assertThat(load("sku-2")).satisfies(row -> {
            assertThat(row.getId()).as("ID сохраняется: строка не пересоздаётся").isEqualTo(id);
            assertThat(row.amount).isEqualByComparingTo("12");
            assertThat(row.getVersion()).isEqualTo(1);
            assertThat(row.getPeriod()).as("ключ не переносится").isEqualTo(PERIOD);
        });
        assertThat(callbacks).containsExactly("beforeSave", "beforeUpdate", "onSave");
        assertThat(distinctOriginals).as("beforeUpdate получает независимый исходный снимок")
            .containsExactly(true);
        assertThat(auditChanges("UPDATE")).singleElement().satisfies(payload ->
            assertThat(payload).contains("10").contains("12"));
    }

    @Test
    void repeatingTheSameResourcesIsANoOpWithoutVersionLifecycleOrAudit() {
        seed("sku-3", "10");
        callbacks.clear();
        published.clear();

        RegisterKeyWrites.Result<PeriodRow> result = command.set(PeriodRow.class,
            Map.of("code", "sku-3"), Map.of("amount", new BigDecimal("10")), PERIOD);

        assertThat(result.verdict()).isEqualTo(RegisterKeyWrites.Verdict.UNCHANGED);
        assertThat(result.written()).isFalse();
        assertThat(load("sku-3").getVersion()).isZero();
        assertThat(callbacks).isEmpty();
        assertThat(published).isEmpty();
        verifyNoInteractions(audit);
    }

    /** Проверяемая версия не создаёт строку и не перезаписывает чужое решение. */
    @Test
    void checkedSetRefusesAnAbsentKeyAndAVersionMismatch() {
        RegisterKeyWrites.Result<PeriodRow> absent = command.set(PeriodRow.class,
            Map.of("code", "sku-4"), Map.of("amount", BigDecimal.ONE), PERIOD, 0L);

        assertThat(absent.verdict()).isEqualTo(RegisterKeyWrites.Verdict.ABSENT);
        assertThat(load("sku-4")).isNull();

        seed("sku-5", "10");
        RegisterKeyWrites.Result<PeriodRow> stale = command.set(PeriodRow.class,
            Map.of("code", "sku-5"), Map.of("amount", new BigDecimal("99")), PERIOD, 7L);

        assertThat(stale.verdict()).isEqualTo(RegisterKeyWrites.Verdict.VERSION_CONFLICT);
        assertThat(load("sku-5").amount).isEqualByComparingTo("10");
        assertThat(load("sku-5").getVersion()).isZero();
        verifyNoInteractions(audit);
    }

    @Test
    void deleteRequiresTheVersionAndTreatsAbsenceAsAVerdict() {
        seed("sku-6", "10");

        assertThat(command.delete(PeriodRow.class, Map.of("code", "sku-6"), PERIOD, null).verdict())
            .isEqualTo(RegisterKeyWrites.Verdict.VERSION_REQUIRED);
        assertThat(command.delete(PeriodRow.class, Map.of("code", "sku-6"), PERIOD, 5L).verdict())
            .isEqualTo(RegisterKeyWrites.Verdict.VERSION_CONFLICT);
        assertThat(load("sku-6")).as("отказ не удаляет строку").isNotNull();

        assertThat(command.delete(PeriodRow.class, Map.of("code", "sku-6"), PERIOD, 0L).verdict())
            .isEqualTo(RegisterKeyWrites.Verdict.DELETED);
        assertThat(load("sku-6")).isNull();
        assertThat(callbacks).contains("beforeDelete");
        assertThat(auditChanges("DELETE")).singleElement().satisfies(payload ->
            assertThat(payload).contains("sku-6"));

        assertThat(command.delete(PeriodRow.class, Map.of("code", "sku-6"), PERIOD, 0L).verdict())
            .as("повторное удаление — вердикт, а не исключение")
            .isEqualTo(RegisterKeyWrites.Verdict.ABSENT);
    }

    /** Другой ключ — другая строка: команда не переносит существующую запись. */
    @Test
    void anotherKeyCreatesAnotherRowAndLeavesTheFirstOneUntouched() {
        Long first = seed("sku-7", "10");

        RegisterKeyWrites.Result<PeriodRow> second = command.set(PeriodRow.class,
            Map.of("code", "sku-8"), Map.of("amount", new BigDecimal("20")), PERIOD);

        assertThat(second.verdict()).isEqualTo(RegisterKeyWrites.Verdict.CREATED);
        assertThat(load("sku-7")).satisfies(row -> {
            assertThat(row.getId()).isEqualTo(first);
            assertThat(row.amount).isEqualByComparingTo("10");
            assertThat(row.getVersion()).isZero();
        });
        assertThat(rowCount()).isEqualTo(2);
    }

    /**
     * Гонка отсутствующего ключа на H2: первая попытка видит пустой ключ и проигрывает
     * физическому UNIQUE, вторая видит строку и заменяет ресурсы. Откатанная попытка не
     * оставляет ни аудита, ни второго события.
     */
    @Test
    void aLostInsertRaceIsRetriedInANewTransactionAndConvergesToAnUpdate() {
        Long id = seed("sku-10", "10");
        callbacks.clear();
        published.clear();
        AtomicInteger reads = new AtomicInteger();
        EntityDataAccess racing = racingAccess(reads);

        RegisterKeyWrites.Result<PeriodRow> result = command(racing, permissions, 3).set(PeriodRow.class,
            Map.of("code", "sku-10"), Map.of("amount", new BigDecimal("15")), PERIOD);

        assertThat(result.verdict()).isEqualTo(RegisterKeyWrites.Verdict.UPDATED);
        assertThat(reads.get()).as("первая попытка — пустой ключ, вторая — строка").isGreaterThanOrEqualTo(2);
        assertThat(rowCount()).isEqualTo(1);
        assertThat(load("sku-10")).satisfies(row -> {
            assertThat(row.getId()).isEqualTo(id);
            assertThat(row.amount).isEqualByComparingTo("15");
            assertThat(row.getVersion()).isEqualTo(1);
        });
        assertThat(published.stream().filter(EntityChangedEvent.class::isInstance))
            .as("откатанная попытка не публикует своё событие").hasSize(1);
        assertThat(auditChanges("UPDATE")).hasSize(1);
    }

    /** Исчерпание бюджета — вердикт; строка и аудит откатанных попыток не остаются. */
    @Test
    void anExhaustedRetryBudgetIsAVerdictNotAnException() {
        seed("sku-11", "10");
        callbacks.clear();
        published.clear();

        RegisterKeyWrites.Result<PeriodRow> result = command(neverSeeingTheRow(), permissions, 3).set(
            PeriodRow.class, Map.of("code", "sku-11"), Map.of("amount", new BigDecimal("15")), PERIOD);

        assertThat(result.verdict()).as("три проигранные гонки вставки — исчерпанный бюджет")
            .isEqualTo(RegisterKeyWrites.Verdict.RETRY_EXHAUSTED);
        assertThat(result.written()).isFalse();
        assertThat(load("sku-11")).satisfies(row -> {
            assertThat(row.amount).isEqualByComparingTo("10");
            assertThat(row.getVersion()).isZero();
        });
        assertThat(rowCount()).isEqualTo(1);
        assertThat(published.stream().filter(event -> event instanceof EntityChangedEvent
            || event instanceof org.ipro.events.EntityDeletedEvent))
            .as("ни одна откатанная попытка не доставляет committed-событие")
            .isEmpty();
        verifyNoInteractions(audit);
    }

    /** Тип без keyWriteAllowed не приобретает ни операций, ни записи. */
    @Test
    void declarationWithoutOptInIsRefusedBeforeAnyPermissionCheck() {
        RegisterKeyWritePermissions real = realPermissions(mock(C5PermissionDecisionService.class));
        RegisterDescriptor descriptor = RegisterDescriptor.describe(ClosedRow.class);

        assertThat(descriptor.keyWriteAllowed()).isFalse();
        assertThatThrownBy(() -> real.requireSet(descriptor))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("keyWriteAllowed");
        assertThatThrownBy(() -> real.requireDelete(descriptor))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("keyWriteAllowed");
        assertThat(rowCount()).isZero();
        verifyNoInteractions(audit);
    }

    /** Отказ C5 останавливает команду до записи: ни строки, ни аудита, ни RLS-фиксации. */
    @Test
    void aC5RefusalStopsBeforeAnyWrite() {
        C5PermissionDecisionService decisions = mock(C5PermissionDecisionService.class);
        doThrow(new AccessDeniedException("SET_KEY denied"))
            .when(decisions).requireAction("key-write-test", RegisterKeyWritePermissions.setPermissionId(PeriodRow.class));

        RegisterKeyWrites guarded = command(readingCanonically(), realPermissions(decisions), 1);

        assertThatThrownBy(() -> guarded.set(PeriodRow.class, Map.of("code", "sku-12"),
            Map.of("amount", BigDecimal.ONE), PERIOD))
            .isInstanceOf(AccessDeniedException.class);
        assertThat(load("sku-12")).isNull();
        verifyNoInteractions(audit);
        verify(policies, never()).recordCanonicalWrite(any());
    }

    /** Неизвестный ключ отвергается до данных: неполный, лишний или неверного типа. */
    @Test
    void malformedKeysAreRefusedBeforeData() {
        assertThatThrownBy(() -> command.set(PeriodRow.class, Map.of("other", "x"),
            Map.of("amount", BigDecimal.ONE), PERIOD))
            .isInstanceOf(ValidationException.class).hasMessageContaining("KEY_WRITE_KEY");
        assertThatThrownBy(() -> command.set(PeriodRow.class, Map.of("code", "sku"),
            Map.of("amount", BigDecimal.ONE), null))
            .isInstanceOf(ValidationException.class).hasMessageContaining("KEY_WRITE_KEY");
        assertThatThrownBy(() -> command.set(PeriodRow.class, Map.of("code", 42),
            Map.of("amount", BigDecimal.ONE), PERIOD))
            .isInstanceOf(ValidationException.class).hasMessageContaining("KEY_WRITE_VALUE_TYPE");
        assertThatThrownBy(() -> command.set(PeriodRow.class, Map.of("code", "sku"),
            Map.of("price", BigDecimal.ONE), PERIOD))
            .isInstanceOf(ValidationException.class).hasMessageContaining("KEY_WRITE_RESOURCE_TYPE");
        assertThat(rowCount()).isZero();
    }

    /** Классификация — по SQLState; повторяются только транзиентные отказы. */
    @Test
    void failuresAreClassifiedBySqlStateAndOnlyTransientOnesAreRetried() {
        assertThat(RegisterKeyWrites.classify(synthetic("23505")))
            .isEqualTo(RegisterKeyWrites.Verdict.CONCURRENT_INSERT);
        assertThat(RegisterKeyWrites.classify(synthetic("40001")))
            .isEqualTo(RegisterKeyWrites.Verdict.SERIALIZATION_FAILURE);
        assertThat(RegisterKeyWrites.classify(synthetic("40P01")))
            .isEqualTo(RegisterKeyWrites.Verdict.DEADLOCK);
        assertThat(RegisterKeyWrites.classify(synthetic("55P03")))
            .isEqualTo(RegisterKeyWrites.Verdict.LOCK_TIMEOUT);
        assertThat(RegisterKeyWrites.classify(new PersistenceException("без SQLState")))
            .as("незнакомый отказ не лечится повтором").isNull();
        assertThat(RegisterKeyWrites.classify(new jakarta.persistence.OptimisticLockException("версия")))
            .isEqualTo(RegisterKeyWrites.Verdict.VERSION_CONFLICT);

        assertThat(RegisterKeyWrites.retryable(RegisterKeyWrites.Verdict.CONCURRENT_INSERT)).isTrue();
        assertThat(RegisterKeyWrites.retryable(RegisterKeyWrites.Verdict.SERIALIZATION_FAILURE)).isTrue();
        assertThat(RegisterKeyWrites.retryable(RegisterKeyWrites.Verdict.DEADLOCK)).isTrue();
        assertThat(RegisterKeyWrites.retryable(RegisterKeyWrites.Verdict.LOCK_TIMEOUT)).isFalse();
        assertThat(RegisterKeyWrites.retryable(RegisterKeyWrites.Verdict.VERSION_CONFLICT)).isFalse();
        assertThat(RegisterKeyWrites.retryable(RegisterKeyWrites.Verdict.ABSENT)).isFalse();
    }

    // === фикстура ===

    /** Первое чтение ключа возвращает «пусто» ровно один раз: проигранная гонка вставки. */
    @SuppressWarnings("unchecked")
    private EntityDataAccess racingAccess(AtomicInteger reads) {
        var access = mock(EntityDataAccess.class);
        when(access.first(eq(PeriodRow.class), any(), any(Sort.class))).thenAnswer(invocation -> {
            if (reads.incrementAndGet() == 1) return Optional.empty();
            return query(invocation.getArgument(1), invocation.getArgument(2));
        });
        when(access.detail(any(), any())).thenAnswer(invocation ->
            Optional.ofNullable(em.find((Class<Object>) invocation.getArgument(0), invocation.getArgument(1))));
        return access;
    }

    /** Ключ «не виден» до конца бюджета: проигранная гонка не сходится, бюджет исчерпывается. */
    @SuppressWarnings("unchecked")
    private EntityDataAccess neverSeeingTheRow() {
        var access = mock(EntityDataAccess.class);
        when(access.first(eq(PeriodRow.class), any(), any(Sort.class))).thenReturn(Optional.empty());
        when(access.detail(any(), any())).thenAnswer(invocation ->
            Optional.ofNullable(em.find((Class<Object>) invocation.getArgument(0), invocation.getArgument(1))));
        return access;
    }

    private RegisterKeyWritePermissions realPermissions(C5PermissionDecisionService decisions) {
        return new RegisterKeyWritePermissions(registers, mock(C5OperationPermissionRegistry.class),
            mock(RlsDimensionRegistry.class), decisions, (RlsCurrentUser) () -> "key-write-test");
    }

    private Long seed(String code, String amount) {
        return new TransactionTemplate(manager).execute(status -> {
            PeriodRow row = new PeriodRow(code, PERIOD, new BigDecimal(amount));
            em.persist(row);
            em.flush();
            return row.getId();
        });
    }

    private PeriodRow load(String code) {
        return new TransactionTemplate(manager).execute(status ->
            em.createQuery("select r from RegisterKeyWriteRow r where r.code = :code", PeriodRow.class)
                .setParameter("code", code).getResultList().stream().findFirst().orElse(null));
    }

    private long rowCount() {
        return new TransactionTemplate(manager).execute(status ->
            em.createQuery("select count(r) from RegisterKeyWriteRow r", Long.class).getSingleResult());
    }

    private List<String> auditChanges(String verb) {
        ArgumentCaptor<FieldChangeRecord> records = ArgumentCaptor.forClass(FieldChangeRecord.class);
        verify(audit, atLeastOnce()).acceptFieldChangeDurable(records.capture());
        return records.getAllValues().stream().filter(record -> verb.equals(record.changeType()))
            .map(FieldChangeRecord::payload).toList();
    }

    private static PersistenceException synthetic(String sqlState) {
        return new PersistenceException("synthetic", new SQLException("synthetic", sqlState));
    }

    private final class Recorder implements EntityLifecycle<PeriodRow> {
        @Override public Class<PeriodRow> entityType() { return PeriodRow.class; }

        @Override public void beforeSave(EntitySaveContext<PeriodRow> context) { callbacks.add("beforeSave"); }

        @Override public void beforeUpdate(EntityUpdateContext<PeriodRow> context) {
            callbacks.add("beforeUpdate");
            distinctOriginals.add(context.hasDistinctOriginal());
        }

        @Override public void beforeDelete(EntityDeleteContext<PeriodRow> context) {
            callbacks.add("beforeDelete");
        }

        @Override public void onSave(EntitySaveContext<PeriodRow> context) { callbacks.add("onSave"); }
    }

    @Entity(name = "RegisterKeyWriteRow")
    @Table(name = "register_key_write_row", uniqueConstraints = @UniqueConstraint(
        name = "uk_register_key_write_row", columnNames = {"business_code", "period_date"}))
    @RegisterMetadata(dimensions = "code", period = "period", resources = "amount", keyWriteAllowed = true)
    static class PeriodRow extends PeriodicRegisterEntryBase {
        @Column(name = "business_code", nullable = false, updatable = false) String code;
        @Column(name = "amount", nullable = false) BigDecimal amount;

        PeriodRow() { }

        PeriodRow(String code, LocalDateTime period, BigDecimal amount) {
            this.code = code;
            setPeriod(period);
            this.amount = amount;
        }
    }

    /** Тот же регистр без opt-in: возможность не появляется из факта объявления. */
    @Entity(name = "RegisterKeyWriteClosedRow")
    @Table(name = "register_key_write_closed_row", uniqueConstraints = @UniqueConstraint(
        name = "uk_register_key_write_closed_row", columnNames = {"business_code"}))
    @RegisterMetadata(dimensions = "code", resources = "amount")
    static class ClosedRow extends BaseEntity implements IdentifiableEntity<Long> {
        @Column(name = "business_code", nullable = false, updatable = false) String code;
        @Column(name = "amount", nullable = false) BigDecimal amount;
    }
}
