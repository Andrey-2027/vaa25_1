package org.ipro.register;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.StaleStateException;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.ipro.crud.BaseEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Р11, PostgreSQL-спайк: запись регистра по ключу (set / upsert / delete) на реальном сервере.
 *
 * <p><b>Что это и чем не является.</b> Это спайк, а не реализация: прототип записи живёт в
 * этом тестовом классе и работает с реальными PostgreSQL-транзакциями по объявленному
 * регистру-фикстуре ({@link KeyRow} проходит настоящую валидацию декларации через
 * {@link RegisterDescriptor#describe}). Проверяется механика сервера и классификация отказов:
 * гонка вставки отсутствующего ключа, сходимость через bounded retry, проверка ожидаемой версии,
 * невозможность продолжить транзакцию после unique violation, полный откат попытки,
 * отсутствие дублей аудита при повторе, resource-only запись без переноса ключа, независимость
 * разных ключей и канонический порядок захвата блокировок, исчерпание бюджета повторов.
 * Интеграция в canonical write pipeline, права, lifecycle и публичный API — следующий пакет Р11
 * (см. ADR-0023); здесь их намеренно нет.</p>
 *
 * <p><b>Почему только реальный сервер.</b> H2 не заменяет эту проверку: арбитром гонки
 * отсутствующего ключа является физический UNIQUE и его семантика блокировки вставки, а
 * «продолжить нельзя» — следствие того, что PostgreSQL завершает транзакцию на любой ошибке
 * оператора (25P02). Приёмочная среда — изолированная база {@code g3_register_test_*} через
 * {@code scripts/register-r11-postgresql.ps1}; без неё класс пропускается.</p>
 */
class RegisterKeyWriteConcurrencyPostgresIT {

    private static final LocalDateTime PERIOD = LocalDateTime.of(2026, 10, 12, 12, 0);
    private static final int LOCK_TIMEOUT_MILLIS = 1_000;

    private StandardServiceRegistry registry;
    private EntityManagerFactory factory;
    private EntityManager em;
    private JpaTransactionManager manager;

    private final CountDownLatch paused = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

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
        factory = new MetadataSources(registry).addAnnotatedClass(KeyRow.class)
            .addAnnotatedClass(KeyWriteAudit.class).buildMetadata().buildSessionFactory();
        manager = new JpaTransactionManager(factory);
        em = SharedEntityManagerCreator.createSharedEntityManager(factory);
        tx(() -> {
            em.createQuery("delete from R11KeyWriteAudit").executeUpdate();
            em.createQuery("delete from R11KeyWriteRow").executeUpdate();
            return null;
        });
    }

    @AfterEach
    void close() {
        if (factory != null) factory.close();
        if (registry != null) StandardServiceRegistryBuilder.destroy(registry);
    }

    /** Декларация спайка — настоящий периодический регистр: ключ (измерение + период) и физический UNIQUE. */
    @Test
    void spikeFixtureIsADeclaredIndependentPeriodicRegisterWithAPhysicalKey() {
        RegisterDescriptor descriptor = RegisterDescriptor.describe(KeyRow.class);

        assertThat(descriptor.periodic()).isTrue();
        assertThat(descriptor.recorded()).isFalse();
        assertThat(descriptor.dimensionNames()).containsExactly("workshopCode");
        assertThat(descriptor.resources()).extracting(RegisterDescriptor.FieldDescriptor::name)
            .containsExactly("amount");
        assertThat(descriptor.rowKeyColumns()).containsExactly("workshop_code", "period_date");
        assertThat(descriptor.genericWritesAllowed()).isTrue();
    }

    /**
     * Два конкурента пишут один отсутствующий ключ. Победитель — вставка, проигравший получает
     * классифицированный отказ: блокировать отсутствующую строку нечем, арбитр — физический UNIQUE.
     */
    @RepeatedTest(3)
    void concurrentInsertOfOneAbsentKeyHasOneWinnerAndTheLoserIsClassified() throws Exception {
        KeyWriter winner = pausingWriter(1);
        KeyWriter loser = writer(1);

        Throwable failure = race(() -> winner.set("w-insert", 10, null), () -> loser.set("w-insert", 20, null));

        assertThat(failure).isNull();
        assertThat(winner.lastVerdict()).isEqualTo(Verdict.CREATED);
        assertThat(loser.lastVerdict()).isEqualTo(Verdict.CONCURRENT_INSERT);
        assertThat(rows()).singleElement().satisfies(row -> {
            assertThat(row.workshopCode).isEqualTo("w-insert");
            assertThat(row.amount).isEqualTo(10);
            assertThat(row.getVersion()).isZero();
        });
        assertThat(audits()).singleElement().satisfies(audit -> assertThat(audit.amount).isEqualTo(10));
    }

    /**
     * Тот же проигравший, но с бюджетом повторов: второй попытке строка уже видна, поэтому
     * безверсионный set становится обновлением ресурсов, а не отказом. Отдельно проверяется, что
     * неудачная попытка не оставила ни строки, ни записи аудита.
     */
    @RepeatedTest(3)
    void loserOfTheInsertRaceConvergesThroughBoundedRetryWithoutDuplicatingTheWrite() throws Exception {
        KeyWriter winner = pausingWriter(1);
        KeyWriter loser = writer(3);

        Throwable failure = race(() -> winner.set("w-retry", 10, null), () -> loser.set("w-retry", 20, null));

        assertThat(failure).isNull();
        assertThat(winner.lastVerdict()).isEqualTo(Verdict.CREATED);
        assertThat(loser.lastVerdict()).isEqualTo(Verdict.UPDATED);
        assertThat(loser.attempted()).as("первая попытка отказала, вторая сошлась").isEqualTo(2);
        assertThat(loser.attemptVerdicts()).containsExactly(Verdict.CONCURRENT_INSERT, Verdict.UPDATED);
        assertThat(rows()).singleElement().satisfies(row -> assertThat(row.amount).isEqualTo(20));
        assertThat(audits()).as("откатанная попытка не оставила записи аудита, осталось создание и обновление")
            .hasSize(2);
    }

    /**
     * Проверяемая версия: оба читают одну версию, побеждает один, проигравший не повторяется
     * автоматически — иначе bounded retry молча затирал бы чужое решение пользователя.
     */
    @RepeatedTest(3)
    void checkedUpdateHasOneWinnerAndTheLoserIsNotRetried() throws Exception {
        assertThat(writer(1).set("w-checked", 10, null)).isEqualTo(Verdict.CREATED);
        KeyWriter winner = pausingWriter(3);
        KeyWriter loser = writer(3);

        Throwable failure = race(() -> winner.set("w-checked", 20, 0L), () -> loser.set("w-checked", 30, 0L));

        assertThat(failure).isNull();
        assertThat(winner.lastVerdict()).isEqualTo(Verdict.UPDATED);
        assertThat(loser.lastVerdict()).isEqualTo(Verdict.VERSION_CONFLICT);
        assertThat(loser.attempted()).as("конфликт версии — не транзиентный отказ").isEqualTo(1);
        assertThat(row("w-checked")).satisfies(row -> {
            assertThat(row.amount).isEqualTo(20);
            assertThat(row.getVersion()).isEqualTo(1);
        });
    }

    /**
     * Правило сервера: любая ошибка оператора завершает транзакцию PostgreSQL, поэтому после
     * unique violation продолжить её нельзя, а повтор обязан начаться новой транзакцией.
     * Проверяется на отдельной пробной таблице тем же соединением — без участия ORM.
     */
    @Test
    void postgresRefusesToContinueATransactionAfterAStatementError() {
        AtomicReference<SQLException> aborted = new AtomicReference<>();
        EntityManager own = factory.createEntityManager();
        try {
            own.getTransaction().begin();
            own.unwrap(org.hibernate.Session.class).doWork(connection -> {
                try (var statement = connection.createStatement()) {
                    statement.execute("create temporary table r11_abort_probe (id integer primary key)"
                        + " on commit preserve rows");
                    statement.execute("insert into r11_abort_probe (id) values (1)");
                    try {
                        statement.execute("insert into r11_abort_probe (id) values (1)");
                        throw new IllegalStateException("дубль ключа не отказал");
                    } catch (SQLException violation) {
                        assertThat(violation.getSQLState()).isEqualTo("23505");
                    }
                    // Продолжение испорченной транзакции: сервер обязан отказать.
                    try {
                        statement.execute("select id from r11_abort_probe");
                    } catch (SQLException failure) {
                        aborted.set(failure);
                    }
                    Throwable refused = aborted.get();
                    assertThat(refused).as("продолжение отказано сервером").isNotNull();
                    assertThat(aborted.get().getSQLState()).as("транзакция PostgreSQL испорчена").isEqualTo("25P02");
                }
            });
            own.getTransaction().rollback();
        } finally {
            own.close();
        }
    }

    /**
     * Та же последовательность через ORM: неудачная попытка не оставляет ни строки, ни аудита,
     * а продолжение работы в той же транзакции отказано — повтор повтором, а транзакция новая.
     */
    @Test
    void uniqueViolationRollsBackTheWholeAttemptAndRefusesContinuation() {
        List<String> states = new ArrayList<>();
        AtomicReference<Throwable> continuation = new AtomicReference<>();
        Throwable failure = catchThrowable(() -> tx(() -> {
            em.persist(new KeyRow("w-abort", PERIOD, 1));
            em.flush();
            try {
                em.persist(new KeyRow("w-abort", PERIOD, 2));
                em.flush();
            } catch (PersistenceException violation) {
                states.addAll(sqlStates(violation));
                // Намеренно продолжаем испорченную транзакцию: продолжение обязано быть отказано.
                continuation.set(catchThrowable(() -> em.createQuery(
                    "select count(r) from R11KeyWriteRow r", Long.class).getSingleResult()));
                return null;
            }
            throw new IllegalStateException("вторая вставка того же ключа не отказала");
        }));

        assertThat(states).as("первый отказ — уникальность ключа").contains("23505");
        assertThat(continuation.get()).as("продолжение той же транзакции отказано").isNotNull();
        assertThat(failure).as("попытка не завершается успешно: транзакция уже откатана").isNotNull();
        assertThat(rows()).as("откат попытки не оставляет ни одной строки").isEmpty();
        assertThat(audits()).isEmpty();
    }

    /** Полный откат попытки: строка и аудит живут в одной транзакции и исчезают вместе. */
    @Test
    void failedAttemptLeavesNeitherRowNorAuditRecord() {
        Throwable failure = catchThrowable(() -> tx(() -> {
            em.persist(new KeyRow("w-rollback", PERIOD, 5));
            em.persist(new KeyWriteAudit("SET", "w-rollback", 5));
            em.flush();
            throw new IllegalStateException("отказ после записи");
        }));

        assertThat(failure).isInstanceOf(IllegalStateException.class);
        assertThat(rows()).isEmpty();
        assertThat(audits()).isEmpty();
    }

    /**
     * set меняет только ресурсы: строка никогда не «переезжает» на другой ключ — другой ключ это
     * другая строка, а перенос ключа остаётся отдельной командой ADR-0021. Проверяемая версия на
     * отсутствующем ключе отказывает вместо неявного создания.
     */
    @Test
    void resourceOnlySetNeverMovesTheKeyAndCheckedSetDoesNotCreateRows() {
        KeyWriter writer = writer(2);

        assertThat(writer.set("w-a", 10, null)).isEqualTo(Verdict.CREATED);
        Long id = row("w-a").getId();
        assertThat(writer.set("w-a", 10, null)).as("повтор тех же ресурсов — no-op").isEqualTo(Verdict.UNCHANGED);
        assertThat(writer.set("w-b", 20, null)).isEqualTo(Verdict.CREATED);
        assertThat(row("w-a")).satisfies(row -> {
            assertThat(row.getId()).isEqualTo(id);
            assertThat(row.amount).isEqualTo(10);
            assertThat(row.getVersion()).isZero();
        });
        assertThat(writer.set("w-a", 99, 7L)).isEqualTo(Verdict.VERSION_CONFLICT);
        assertThat(writer.set("w-c", 30, 0L)).as("проверяемая версия не создаёт строку")
            .isEqualTo(Verdict.ABSENT);
        assertThat(rows()).extracting(row -> row.workshopCode).containsExactly("w-a", "w-b");
        assertThat(audits()).hasSize(2);
    }

    /** delete по ключу: версия обязательна, конфликт версии не повторяется, отсутствие — ABSENT. */
    @Test
    void deleteByKeyRequiresTheVersionAndTreatsAbsenceAsAVerdict() {
        KeyWriter writer = writer(2);
        assertThat(writer.set("w-del", 10, null)).isEqualTo(Verdict.CREATED);
        Long id = row("w-del").getId();

        assertThat(writer.delete("w-del", null)).isEqualTo(Verdict.VERSION_REQUIRED);
        assertThat(writer.delete("w-del", 5L)).isEqualTo(Verdict.VERSION_CONFLICT);
        assertThat(row("w-del").getId()).isEqualTo(id);
        assertThat(writer.delete("w-del", 0L)).isEqualTo(Verdict.DELETED);
        assertThat(rows()).isEmpty();
        assertThat(writer.delete("w-del", 0L)).as("повторное удаление отсутствующей строки").isEqualTo(Verdict.ABSENT);
        assertThat(audits()).extracting(audit -> audit.operation).containsExactly("SET", "DELETE");
    }

    /**
     * Набор ключей берётся в каноническом порядке: два набора одних ключей в разном порядке
     * коммитятся оба, без deadlock и без частично применённого набора. Разные ключи не мешают
     * друг другу: конкурентные вставки в непересекающиеся ключи обе успешны.
     */
    @RepeatedTest(3)
    void canonicalBatchOrderAvoidsDeadlockAndDifferentKeysDoNotConflict() throws Exception {
        assertThat(writer(1).setAll(List.of("w-x", "w-y"), 5).values()).containsOnly(Verdict.CREATED);
        KeyWriter first = writer(1);
        KeyWriter second = writer(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> a = pool.submit(() -> run(() -> first.setAll(List.of("w-x", "w-y"), 7)));
            Future<Throwable> b = pool.submit(() -> run(() -> second.setAll(List.of("w-y", "w-x"), 9)));
            assertThat(a.get(30, TimeUnit.SECONDS)).isNull();
            assertThat(b.get(30, TimeUnit.SECONDS)).isNull();
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
        long x = row("w-x").amount;
        assertThat(row("w-y").amount).as("набор применён целиком одним из конкурентов").isEqualTo(x);

        KeyWriter left = writer(1);
        KeyWriter right = writer(1);
        ExecutorService disjoint = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> a = disjoint.submit(() -> run(() -> left.set("w-left", 1, null)));
            Future<Throwable> b = disjoint.submit(() -> run(() -> right.set("w-right", 2, null)));
            assertThat(a.get(30, TimeUnit.SECONDS)).isNull();
            assertThat(b.get(30, TimeUnit.SECONDS)).isNull();
        } finally {
            disjoint.shutdownNow();
            assertThat(disjoint.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(left.lastVerdict()).isEqualTo(Verdict.CREATED);
        assertThat(right.lastVerdict()).isEqualTo(Verdict.CREATED);
    }

    /**
     * Бюджет повторов ограничен: конкурент забирает ключ перед каждой попыткой, обе попытки
     * получают настоящий 23505, после чего команда возвращает RETRY_EXHAUSTED и не оставляет
     * ни своей строки, ни записи аудита.
     */
    @Test
    void retryBudgetIsBoundedWhenACompetitorKeepsTakingTheKey() throws Exception {
        int maxAttempts = 2;
        CountDownLatch[] read = latches(maxAttempts);
        CountDownLatch[] inserted = latches(maxAttempts);
        CountDownLatch[] deleted = latches(maxAttempts);
        CountDownLatch[] failed = latches(maxAttempts);
        KeyWriter writer = writer(maxAttempts);
        writer.beforeAttempt(attempt -> {
            // Ключ действительно снова свободен: конкурент удалил свою строку до нашего чтения.
            if (attempt > 1) await(deleted[attempt - 2]);
        });
        writer.afterRead(attempt -> {
            // Конкурент вставляет строго между нашим чтением и нашей вставкой.
            read[attempt - 1].countDown();
            await(inserted[attempt - 1]);
        });
        writer.afterFailedAttempt((attempt, verdict) -> failed[attempt - 1].countDown());

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> competitor = pool.submit(() -> run(() -> {
                for (int round = 0; round < maxAttempts; round++) {
                    await(read[round]);
                    competingInsert("w-taken", 999);
                    inserted[round].countDown();
                    if (round + 1 < maxAttempts) {
                        await(failed[round]);
                        competingDelete("w-taken");
                        deleted[round].countDown();
                    }
                }
            }));
            assertThat(writer.set("w-taken", 10, null)).isEqualTo(Verdict.RETRY_EXHAUSTED);
            assertThat(competitor.get(30, TimeUnit.SECONDS)).isNull();
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(writer.attempted()).isEqualTo(maxAttempts);
        assertThat(writer.attemptVerdicts()).containsExactly(Verdict.CONCURRENT_INSERT, Verdict.CONCURRENT_INSERT);
        assertThat(row("w-taken").amount).as("победила строка конкурента, наша запись не применена").isEqualTo(999);
        assertThat(audits()).isEmpty();
    }

    /** Классификация и политика повторов — по SQLState, а не по типу исключения фреймворка. */
    @Test
    void failuresAreClassifiedBySqlStateAndOnlyTransientOnesAreRetried() {
        assertThat(KeyWriter.classify(synthetic("23505"))).isEqualTo(Verdict.CONCURRENT_INSERT);
        assertThat(KeyWriter.classify(synthetic("40001"))).isEqualTo(Verdict.SERIALIZATION_FAILURE);
        assertThat(KeyWriter.classify(synthetic("40P01"))).isEqualTo(Verdict.DEADLOCK);
        assertThat(KeyWriter.classify(synthetic("55P03"))).isEqualTo(Verdict.LOCK_TIMEOUT);
        assertThat(KeyWriter.classify(synthetic("25P02"))).isEqualTo(Verdict.TRANSACTION_ABORTED);
        assertThat(KeyWriter.classify(new PersistenceException("без SQLState"))).isEqualTo(Verdict.UNCLASSIFIED);
        assertThat(KeyWriter.classify(new OptimisticLockException("версия"))).isEqualTo(Verdict.VERSION_CONFLICT);
        assertThat(KeyWriter.classify(new PersistenceException(new StaleStateException("stale"))))
            .isEqualTo(Verdict.VERSION_CONFLICT);

        assertThat(KeyWriter.retryable(Verdict.CONCURRENT_INSERT)).isTrue();
        assertThat(KeyWriter.retryable(Verdict.SERIALIZATION_FAILURE)).isTrue();
        assertThat(KeyWriter.retryable(Verdict.DEADLOCK)).isTrue();
        assertThat(KeyWriter.retryable(Verdict.VERSION_CONFLICT)).as("чужое решение не затирается повтором").isFalse();
        assertThat(KeyWriter.retryable(Verdict.LOCK_TIMEOUT)).as("повтор удвоил бы ожидание").isFalse();
        assertThat(KeyWriter.retryable(Verdict.TRANSACTION_ABORTED)).isFalse();
        assertThat(KeyWriter.retryable(Verdict.UNCLASSIFIED)).isFalse();
    }

    // === прототип: только для спайка, не публичный API ===

    private enum Verdict {
        CREATED, UPDATED, UNCHANGED, DELETED, ABSENT, VERSION_REQUIRED, VERSION_CONFLICT,
        CONCURRENT_INSERT, RETRY_EXHAUSTED, LOCK_TIMEOUT, SERIALIZATION_FAILURE, DEADLOCK,
        TRANSACTION_ABORTED, UNCLASSIFIED
    }

    /**
     * Прототип команды записи по ключу. Каждая попытка — своя транзакция: после классифицированного
     * отказа предыдущая транзакция уже откатана и непригодна, поэтому повтор обязан начаться заново.
     */
    private final class KeyWriter {
        private final int maxAttempts;
        private IntConsumer beforeAttempt = attempt -> { };
        private IntConsumer afterRead = attempt -> { };
        private IntConsumer afterWrite = attempt -> { };
        private BiConsumer<Integer, Verdict> afterFailedAttempt = (attempt, verdict) -> { };
        private final List<Verdict> attemptVerdicts = new ArrayList<>();
        private int attempts;
        private Verdict lastVerdict;

        private KeyWriter(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        void beforeAttempt(IntConsumer hook) { beforeAttempt = hook; }

        void afterRead(IntConsumer hook) { afterRead = hook; }

        void afterWrite(IntConsumer hook) { afterWrite = hook; }

        void afterFailedAttempt(BiConsumer<Integer, Verdict> hook) { afterFailedAttempt = hook; }

        int attempted() { return attempts; }

        List<Verdict> attemptVerdicts() { return List.copyOf(attemptVerdicts); }

        Verdict lastVerdict() { return lastVerdict; }

        Verdict set(String code, long amount, Long expectedVersion) {
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                int round = attempt; // счётчик цикла мутируется, а лямбде нужна неизменяемая копия
                attempts = round;
                beforeAttempt.accept(round);
                Verdict verdict;
                try {
                    verdict = tx(() -> setInTransaction(code, amount, expectedVersion, round));
                } catch (PersistenceException failure) {
                    verdict = classify(failure);
                    if (verdict == Verdict.UNCLASSIFIED) throw failure;
                    attemptVerdicts.add(verdict);
                    afterFailedAttempt.accept(round, verdict);
                    if (!retryable(verdict) || round == maxAttempts) {
                        // Бюджет ровно в одну попытку — это не исчерпание: повтора не было,
                        // поэтому честный ответ — классифицированный конфликт.
                        return lastVerdict = maxAttempts == 1 || !retryable(verdict)
                            ? verdict : Verdict.RETRY_EXHAUSTED;
                    }
                    continue;
                }
                attemptVerdicts.add(verdict);
                return lastVerdict = verdict;
            }
            return lastVerdict = Verdict.RETRY_EXHAUSTED;
        }

        Verdict delete(String code, Long expectedVersion) {
            return lastVerdict = tx(() -> {
                KeyRow row = lockedRow(code);
                if (row == null) return Verdict.ABSENT;
                if (expectedVersion == null) return Verdict.VERSION_REQUIRED;
                if (!expectedVersion.equals(row.getVersion())) return Verdict.VERSION_CONFLICT;
                em.remove(row);
                em.flush();
                audit("DELETE", code, 0);
                return Verdict.DELETED;
            });
        }

        /** Набор ключей захватывается в каноническом порядке, поэтому два набора не дают deadlock. */
        Map<String, Verdict> setAll(List<String> codes, long amount) {
            List<String> canonical = codes.stream().distinct().sorted().toList();
            if (canonical.isEmpty()) return Map.of();
            return tx(() -> {
                Map<String, Verdict> result = new LinkedHashMap<>();
                for (String code : canonical) {
                    KeyRow row = lockedRow(code);
                    if (row == null) {
                        em.persist(new KeyRow(code, PERIOD, amount));
                        result.put(code, Verdict.CREATED);
                    } else if (row.amount == amount) {
                        result.put(code, Verdict.UNCHANGED);
                    } else {
                        row.amount = amount;
                        result.put(code, Verdict.UPDATED);
                    }
                }
                em.flush();
                canonical.forEach(code -> audit("SET", code, amount));
                return result;
            });
        }

        private Verdict setInTransaction(String code, long amount, Long expectedVersion, int attempt) {
            KeyRow row = lockedRow(code);
            afterRead.accept(attempt);
            if (row == null) {
                if (expectedVersion != null) return Verdict.ABSENT;
                em.persist(new KeyRow(code, PERIOD, amount));
                em.flush(); // Гонку отсутствующего ключа решает физический UNIQUE, а не блокировка.
                audit("SET", code, amount);
                afterWrite.accept(attempt);
                return Verdict.CREATED;
            }
            if (expectedVersion != null && !expectedVersion.equals(row.getVersion())) return Verdict.VERSION_CONFLICT;
            if (row.amount == amount) return Verdict.UNCHANGED;
            row.amount = amount;
            em.flush();
            audit("SET", code, amount);
            afterWrite.accept(attempt);
            return Verdict.UPDATED;
        }

        private KeyRow lockedRow(String code) {
            var query = em.createQuery(
                "select r from R11KeyWriteRow r where r.workshopCode = :code and r.period = :period order by r.id",
                KeyRow.class);
            query.setParameter("code", code).setParameter("period", PERIOD);
            query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
            query.setHint("jakarta.persistence.lock.timeout", LOCK_TIMEOUT_MILLIS);
            List<KeyRow> found = query.getResultList();
            return found.isEmpty() ? null : found.getFirst();
        }

        static Verdict classify(Throwable failure) {
            List<String> states = sqlStates(failure);
            if (states.contains("23505")) return Verdict.CONCURRENT_INSERT;
            if (states.contains("40001")) return Verdict.SERIALIZATION_FAILURE;
            if (states.contains("40P01")) return Verdict.DEADLOCK;
            if (states.contains("55P03") || states.contains("57014")) return Verdict.LOCK_TIMEOUT;
            if (states.contains("25P02")) return Verdict.TRANSACTION_ABORTED;
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof OptimisticLockException || cause instanceof StaleStateException) {
                    return Verdict.VERSION_CONFLICT;
                }
            }
            return Verdict.UNCLASSIFIED;
        }

        static boolean retryable(Verdict verdict) {
            return verdict == Verdict.CONCURRENT_INSERT || verdict == Verdict.SERIALIZATION_FAILURE
                || verdict == Verdict.DEADLOCK;
        }
    }

    private KeyWriter writer(int maxAttempts) {
        return new KeyWriter(maxAttempts);
    }

    /** Первый конкурент держит запись (вставку или обновление) до коммита, второй встаёт на блокировку. */
    private KeyWriter pausingWriter(int maxAttempts) {
        KeyWriter writer = writer(maxAttempts);
        writer.afterWrite(attempt -> {
            paused.countDown();
            await(release);
        });
        return writer;
    }

    private Throwable race(Runnable first, Runnable second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> a = pool.submit(() -> run(first));
            assertThat(paused.await(15, TimeUnit.SECONDS)).as("первый конкурент дошёл до записи").isTrue();
            Future<Throwable> b = pool.submit(() -> run(second));
            awaitRealWaiter();
            release.countDown();
            assertThat(a.get(30, TimeUnit.SECONDS)).isNull();
            return b.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** Наблюдаем реального ожидающего в PostgreSQL, а не «почти одновременность» по времени. */
    private void awaitRealWaiter() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            boolean waiting = tx(() -> ((Number) em.createNativeQuery(
                "select count(*) from pg_stat_activity where datname = current_database()"
                    + " and cardinality(pg_blocking_pids(pid)) > 0", Long.class).getSingleResult()).longValue() > 0);
            if (waiting) return;
            Thread.sleep(10);
        }
        throw new IllegalStateException("второй конкурент не встал на блокировку PostgreSQL");
    }

    private Throwable run(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    /** Независимая транзакция конкурента: своё соединение, свои commit/rollback. */
    private void competingInsert(String code, long amount) {
        EntityManager own = factory.createEntityManager();
        try {
            own.getTransaction().begin();
            own.persist(new KeyRow(code, PERIOD, amount));
            own.getTransaction().commit();
        } finally {
            own.close();
        }
    }

    private void competingDelete(String code) {
        EntityManager own = factory.createEntityManager();
        try {
            own.getTransaction().begin();
            KeyRow row = own.createQuery("select r from R11KeyWriteRow r where r.workshopCode = :code", KeyRow.class)
                .setParameter("code", code).getSingleResult();
            own.remove(row);
            own.getTransaction().commit();
        } finally {
            own.close();
        }
    }

    private void audit(String operation, String code, long amount) {
        em.persist(new KeyWriteAudit(operation, code, amount));
    }

    private <T> T tx(Callable<T> action) {
        return new TransactionTemplate(manager).execute(status -> {
            try {
                return action.call();
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    private List<KeyRow> rows() {
        return tx(() -> em.createQuery("from R11KeyWriteRow order by workshopCode", KeyRow.class).getResultList());
    }

    private KeyRow row(String code) {
        return tx(() -> em.createQuery("select r from R11KeyWriteRow r where r.workshopCode = :code", KeyRow.class)
            .setParameter("code", code).getResultList().stream().findFirst().orElse(null));
    }

    private List<KeyWriteAudit> audits() {
        return tx(() -> em.createQuery("from R11KeyWriteAudit", KeyWriteAudit.class).getResultList());
    }

    private static PersistenceException synthetic(String sqlState) {
        return new PersistenceException("synthetic", new SQLException("synthetic", sqlState));
    }

    private static List<String> sqlStates(Throwable failure) {
        List<String> states = new ArrayList<>();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) states.add(sql.getSQLState());
        }
        return states;
    }

    private static CountDownLatch[] latches(int size) {
        CountDownLatch[] latches = new CountDownLatch[size];
        for (int index = 0; index < size; index++) latches[index] = new CountDownLatch(1);
        return latches;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("тестовое ожидание истекло");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    @Entity(name = "R11KeyWriteRow")
    @Table(name = "r11_key_write_row", uniqueConstraints = @UniqueConstraint(
        name = "uq_r11_key_write_row_key", columnNames = {"workshop_code", "period_date"}))
    @RegisterMetadata(dimensions = "workshopCode", period = "period", resources = "amount")
    static class KeyRow extends PeriodicRegisterEntryBase {
        @Column(name = "workshop_code", nullable = false, updatable = false) String workshopCode;
        @Column(name = "amount", nullable = false) long amount;

        KeyRow() { }

        KeyRow(String workshopCode, LocalDateTime period, long amount) {
            this.workshopCode = workshopCode;
            setPeriod(period);
            this.amount = amount;
        }
    }

    /** Аудит пишется в транзакцию попытки: откат попытки уносит и его, повтор не удваивает. */
    @Entity(name = "R11KeyWriteAudit")
    @Table(name = "r11_key_write_audit")
    static class KeyWriteAudit extends BaseEntity {
        @Column(name = "operation", nullable = false) String operation;
        @Column(name = "workshop_code", nullable = false) String workshopCode;
        @Column(name = "amount", nullable = false) long amount;

        KeyWriteAudit() { }

        KeyWriteAudit(String operation, String workshopCode, long amount) {
            this.operation = operation;
            this.workshopCode = workshopCode;
            this.amount = amount;
        }
    }
}
