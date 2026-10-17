package org.ipro.register;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.ipro.data.EntityDataAccess;
import org.ipro.events.EntityEventPublisher;
import org.ipro.lifecycle.EntityLifecycleRegistry;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsPolicyEnforcer;
import org.ipro.telemetry.api.EventSink;
import org.ipro.telemetry.api.FieldChangeRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Р11, реализация на реальном PostgreSQL: команда записи по ключу поверх той же канонической
 * последовательности, что и на H2, но с настоящими транзакциями и физическим UNIQUE.
 *
 * <p><b>Зачем отдельный класс, если есть H2-приёмка.</b> H2 не заменяет серверную семантику:
 * арбитр гонки отсутствующего ключа — индекс PostgreSQL, а необратимость транзакции после ошибки
 * оператора (25P02) делает bounded retry возможным только через <b>новую</b> транзакцию на попытку.
 * Поэтому здесь проверяются ровно те два утверждения, которые H2 подтвердить не может: проигранная
 * гонка вставки сходится в UPDATE на второй попытке, а проверяемая версия не перезаписывает
 * уже закоммиченное чужое решение (ожидание на реальной строковой блокировке).</p>
 *
 * <p>Слой решений (C5/RLS) здесь мок: он не зависит от СУБД и проверяется на H2 отдельно. Проверяется
 * серверная часть — bounded retry, версия, отсутствие дублей строки и аудита при откате попытки.</p>
 *
 * <p>Без изолированной базы класс инертен: приёмочная среда — {@code g3_register_test_*} через
 * {@code scripts/register-r11-postgresql.ps1}, без неё класс пропускается.</p>
 */
class RegisterKeyWritesPostgresIT {

    private static final LocalDateTime PERIOD = LocalDateTime.of(2026, 10, 13, 9, 0);

    private StandardServiceRegistry registry;
    private EntityManagerFactory factory;
    private EntityManager em;
    private JpaTransactionManager manager;
    private ValidatorFactory validator;
    private RegisterRegistry registers;
    private RegisterKeyWritePermissions permissions;
    private EventSink audit;
    private final List<FieldChangeRecord> auditRecords = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void connect() {
        String url = System.getenv("IPRO_REGISTER_G3_JDBC_URL");
        Assumptions.assumeTrue(url != null && url.matches(
                "^jdbc:postgresql://(?:localhost|127\\.0\\.0\\.1)(?::[0-9]+)?/g3_register_test_[a-z0-9_]+$"),
            "Requires a disposable local g3_register_test_* database");
        String user = Objects.requireNonNull(System.getenv("IPRO_REGISTER_G3_DB_USER"));
        String password = Objects.requireNonNull(System.getenv("IPRO_REGISTER_G3_DB_PASSWORD"));
        registry = new StandardServiceRegistryBuilder()
            .applySetting("hibernate.connection.driver_class", "org.postgresql.Driver")
            .applySetting("hibernate.connection.url", url)
            .applySetting("hibernate.connection.username", user)
            .applySetting("hibernate.connection.password", password)
            .applySetting("hibernate.connection.pool_size", 8)
            .applySetting("hibernate.hbm2ddl.auto", "create-drop").build();
        factory = new MetadataSources(registry).addAnnotatedClass(CommandRow.class).buildMetadata()
            .buildSessionFactory();
        new RegisterKeyWriteGuardInstaller(factory).afterPropertiesSet();
        manager = new JpaTransactionManager(factory);
        em = SharedEntityManagerCreator.createSharedEntityManager(factory);
        validator = Validation.buildDefaultValidatorFactory();
        registers = mock(RegisterRegistry.class);
        when(registers.require(CommandRow.class)).thenReturn(RegisterDescriptor.describe(CommandRow.class));
        when(registers.find(CommandRow.class)).thenReturn(Optional.of(RegisterDescriptor.describe(CommandRow.class)));
        permissions = mock(RegisterKeyWritePermissions.class);
        audit = mock(EventSink.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            auditRecords.add(invocation.getArgument(0));
            return null;
        }).when(audit).acceptFieldChangeDurable(any(FieldChangeRecord.class));
    }

    @AfterEach
    void close() {
        if (validator != null) validator.close();
        if (factory != null) factory.close();
        if (registry != null) StandardServiceRegistryBuilder.destroy(registry);
    }

    /**
     * Гонка отсутствующего ключа на сервере: первый писатель читает пусто и задерживается, второй
     * создаёт строку и коммитится, первый получает настоящий 23505 и сходится только потому, что
     * повтор идёт <b>новой</b> транзакцией. Откатанная попытка не оставляет ни строки, ни аудита.
     */
    @Test
    void lostInsertRaceIsRetriedInANewTransactionAndTheRowArrivesOnce() throws Exception {
        CountDownLatch loosing = new CountDownLatch(1);
        CountDownLatch committed = new CountDownLatch(1);
        RegisterKeyWrites loser = command(pausingAfterTheFirstRead(loosing, committed));
        RegisterKeyWrites winner = command(readingCanonically());

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<RegisterKeyWrites.Result<CommandRow>> lost = pool.submit(() ->
                loser.set(CommandRow.class, Map.of("workshopCode", "w-race"), Map.of("amount", new BigDecimal("15")), PERIOD));
            await(loosing);
            assertThat(winner.set(CommandRow.class, Map.of("workshopCode", "w-race"),
                Map.of("amount", new BigDecimal("10")), PERIOD).verdict())
                .as("первый коммит побеждает индекс").isEqualTo(RegisterKeyWrites.Verdict.CREATED);
            committed.countDown();

            assertThat(lost.get(60, TimeUnit.SECONDS).verdict())
                .as("вторая попытка видит закоммиченную строку и становится обновлением ресурсов")
                .isEqualTo(RegisterKeyWrites.Verdict.UPDATED);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(rows()).singleElement().satisfies(row -> {
            assertThat(row.workshopCode).isEqualTo("w-race");
            assertThat(row.amount).as("ресурс проигравшего применён, второй строки нет")
                .isEqualByComparingTo("15");
            assertThat(row.getVersion()).isEqualTo(1);
        });
        assertThat(auditRecords).extracting(FieldChangeRecord::changeType)
            .as("откатанная попытка не оставила аудита").containsExactlyInAnyOrder("CREATE", "UPDATE");
    }

    /**
     * Проверяемая версия против уже закоммиченного чужого решения: проигравший ждёт строковую
     * блокировку, видит новую версию и получает отказ, а не перезапись. Автоматического повтора нет.
     */
    @Test
    void checkedSetDoesNotOverwriteAVersionCommittedByAnotherWriter() throws Exception {
        assertThat(command(readingCanonically()).set(CommandRow.class, Map.of("workshopCode", "w-lock"),
            Map.of("amount", new BigDecimal("10")), PERIOD).verdict()).isEqualTo(RegisterKeyWrites.Verdict.CREATED);
        auditRecords.clear();

        CountDownLatch holdingTheLock = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RegisterKeyWrites first = command(pausingAfterTheSecondRead(holdingTheLock, release));
        RegisterKeyWrites second = command(readingCanonically());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<RegisterKeyWrites.Result<CommandRow>> winner = pool.submit(() -> first.set(
                CommandRow.class, Map.of("workshopCode", "w-lock"), Map.of("amount", new BigDecimal("20")), PERIOD, 0L));
            await(holdingTheLock);
            // Второй писатель приходит к уже заблокированной строке с той же исходной версией.
            Future<RegisterKeyWrites.Result<CommandRow>> loser = pool.submit(() -> second.set(
                CommandRow.class, Map.of("workshopCode", "w-lock"), Map.of("amount", new BigDecimal("30")), PERIOD, 0L));
            Thread.sleep(200);
            release.countDown();

            assertThat(winner.get(60, TimeUnit.SECONDS).verdict()).isEqualTo(RegisterKeyWrites.Verdict.UPDATED);
            assertThat(loser.get(60, TimeUnit.SECONDS).verdict())
                .as("чужая версия не перезаписывается").isEqualTo(RegisterKeyWrites.Verdict.VERSION_CONFLICT);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(rows()).singleElement().satisfies(row -> {
            assertThat(row.amount).isEqualByComparingTo("20");
            assertThat(row.getVersion()).isEqualTo(1);
        });
        assertThat(auditRecords).extracting(FieldChangeRecord::changeType).containsExactly("UPDATE");
    }

    /** Удаление по ключу на сервере: версия обязательна, повтор отсутствующей строки — вердикт. */
    @Test
    void deleteByKeyRemovesTheRowOnceAndTheRepeatIsAbsent() {
        RegisterKeyWrites command = command(readingCanonically());
        assertThat(command.set(CommandRow.class, Map.of("workshopCode", "w-delete"),
            Map.of("amount", new BigDecimal("10")), PERIOD).verdict()).isEqualTo(RegisterKeyWrites.Verdict.CREATED);

        assertThat(command.delete(CommandRow.class, Map.of("workshopCode", "w-delete"), PERIOD, null).verdict())
            .isEqualTo(RegisterKeyWrites.Verdict.VERSION_REQUIRED);
        assertThat(command.delete(CommandRow.class, Map.of("workshopCode", "w-delete"), PERIOD, 0L).verdict())
            .isEqualTo(RegisterKeyWrites.Verdict.DELETED);
        assertThat(rows()).isEmpty();
        assertThat(command.delete(CommandRow.class, Map.of("workshopCode", "w-delete"), PERIOD, 0L).verdict())
            .isEqualTo(RegisterKeyWrites.Verdict.ABSENT);
        assertThat(auditRecords).extracting(FieldChangeRecord::changeType).containsExactly("CREATE", "DELETE");
        assertThat(auditRecords.get(1).payload()).as("удаление называет ключ").contains("w-delete");
    }

    /** Разные ключи не мешают друг другу: конкурентные вставки в непересекающиеся ключи обе успешны. */
    @Test
    void differentKeysDoNotConflict() throws Exception {
        RegisterKeyWrites left = command(readingCanonically());
        RegisterKeyWrites right = command(readingCanonically());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<RegisterKeyWrites.Verdict> a = pool.submit(() -> left.set(CommandRow.class,
                Map.of("workshopCode", "w-left"), Map.of("amount", new BigDecimal("1")), PERIOD).verdict());
            Future<RegisterKeyWrites.Verdict> b = pool.submit(() -> right.set(CommandRow.class,
                Map.of("workshopCode", "w-right"), Map.of("amount", new BigDecimal("2")), PERIOD).verdict());
            assertThat(a.get(60, TimeUnit.SECONDS)).isEqualTo(RegisterKeyWrites.Verdict.CREATED);
            assertThat(b.get(60, TimeUnit.SECONDS)).isEqualTo(RegisterKeyWrites.Verdict.CREATED);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(rows()).extracting(row -> row.workshopCode).containsExactlyInAnyOrder("w-left", "w-right");
    }

    // === фикстура и команда ===

    private RegisterKeyWrites command(EntityDataAccess reads) {
        return new RegisterKeyWrites(em, registers, permissions, reads, mock(RlsPolicyEnforcer.class),
            validator.getValidator(), new EntityLifecycleRegistry(List.of()),
            new EntityEventPublisher(event -> { }), (RlsCurrentUser) () -> "key-write-pg-it",
            Optional.of(audit), manager);
    }

    /** Первое чтение ключа возвращает пусто, затем писатель задерживается до чужого коммита. */
    private EntityDataAccess pausingAfterTheFirstRead(CountDownLatch read, CountDownLatch release) {
        return pausing(1, read, release);
    }

    /** Пауза после подтверждающего чтения под пессимистической блокировкой строки. */
    private EntityDataAccess pausingAfterTheSecondRead(CountDownLatch locked, CountDownLatch release) {
        return pausing(2, locked, release);
    }

    @SuppressWarnings("unchecked")
    private EntityDataAccess pausing(int pauseAt, CountDownLatch reached, CountDownLatch release) {
        var access = mock(EntityDataAccess.class);
        AtomicInteger calls = new AtomicInteger();
        when(access.first(eq(CommandRow.class), any(), any(Sort.class))).thenAnswer(invocation -> {
            Optional<CommandRow> row = query(invocation.getArgument(1), invocation.getArgument(2));
            if (calls.incrementAndGet() == pauseAt) {
                reached.countDown();
                await(release);
            }
            return row;
        });
        when(access.detail(any(), any())).thenAnswer(invocation ->
            Optional.ofNullable(em.find((Class<Object>) invocation.getArgument(0), invocation.getArgument(1))));
        return access;
    }

    @SuppressWarnings("unchecked")
    private EntityDataAccess readingCanonically() {
        var access = mock(EntityDataAccess.class);
        when(access.first(eq(CommandRow.class), any(), any(Sort.class))).thenAnswer(invocation ->
            query(invocation.getArgument(1), invocation.getArgument(2)));
        when(access.detail(any(), any())).thenAnswer(invocation ->
            Optional.ofNullable(em.find((Class<Object>) invocation.getArgument(0), invocation.getArgument(1))));
        return access;
    }

    private Optional<CommandRow> query(Specification<CommandRow> specification, Sort sort) {
        var builder = em.getCriteriaBuilder();
        var query = builder.createQuery(CommandRow.class);
        var root = query.from(CommandRow.class);
        query.select(root).where(specification.toPredicate(root, query, builder));
        query.orderBy(sort.stream().map(order -> order.isAscending()
            ? builder.asc(root.get(order.getProperty()))
            : builder.desc(root.get(order.getProperty()))).toList());
        return em.createQuery(query).setMaxResults(1).getResultList().stream().findFirst();
    }

    private List<CommandRow> rows() {
        return new TransactionTemplate(manager).execute(status ->
            em.createQuery("select r from R11KeyWriteCommandRow r", CommandRow.class).getResultList());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) throw new IllegalStateException("тестовое ожидание истекло");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    /** Фикстура — настоящая декларация регистра: ключ (измерение + период) и физический UNIQUE. */
    @Entity(name = "R11KeyWriteCommandRow")
    @Table(name = "r11_key_write_command_row", uniqueConstraints = @UniqueConstraint(
        name = "uq_r11_key_write_command_row", columnNames = {"workshop_code", "period_date"}))
    @RegisterMetadata(dimensions = "workshopCode", period = "period", resources = "amount",
        keyWriteAllowed = true, displayName = "Регистр записи по ключу (PostgreSQL)")
    static class CommandRow extends PeriodicRegisterEntryBase {
        @Column(name = "workshop_code", nullable = false, updatable = false) String workshopCode;
        @Column(name = "amount", nullable = false) BigDecimal amount;

        CommandRow() { }

        CommandRow(String workshopCode, LocalDateTime period, BigDecimal amount) {
            this.workshopCode = workshopCode;
            setPeriod(period);
            this.amount = amount;
        }
    }
}
