package org.ip.config;

import jakarta.persistence.EntityManager;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.ip.Application;
import org.ip.application.register.AccountingObjectSaveService;
import org.ip.model.AccountingObjectInstallation;
import org.ip.model.AccountingObjectInstallationItem;
import org.ip.model.AccountingType;
import org.ip.model.Nomenclature;
import org.ip.model.Workshop;
import org.ip.repository.RoleRepository;
import org.ip.repository.UserRepository;
import org.ipro.crud.MetadataDrivenAggregateSaveService;
import org.ipro.data.EntityDataAccess;
import org.ipro.events.EventSource;
import org.ipro.rls.AccessGrantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C5.6b: численный замер security-запросов на пилоте — сколько обращений к таблицам решений
 * (гранты, роли, пользователь) стоит одно сохранение документа учёта.
 *
 * <p><b>Зачем замер.</b> C5.4 оценил цену решения статически: «не менее восьми запросов к
 * {@code access_grant} на одно сохранение документа». С этой оценкой нельзя сравнивать потолки
 * C5.9 — она не измерена. Замер идёт тем же путём, которым пойдёт человек в пилоте: фикстура
 * {@link AccountingObjectPilotInitializer#seedFixture()} заводит данные от названного субъекта
 * {@code accounting-pilot} с его собственными правами, а измеряется одно сохранение документа
 * через тот же {@link AccountingObjectSaveService}.</p>
 *
 * <p><b>Чем измеряется.</b> Hibernate держит ровно один {@code StatementInspector}, поэтому на
 * время замера он подменён счётчиком ({@code CountingInspector}) — телеметрический инспектор в
 * этом контексте не работает, и это цена измеримости, а не побочный эффект: счётчик печатает
 * текст каждого запроса, а не только число. Считаются все SQL окна, отдельно — запросы к
 * таблицам решений ({@code access_grant}, {@code app_user}, {@code app_role}), а для грантов
 * ещё и повторяемость одного и того же запроса: именно она — вход для C5.9.</p>
 *
 * <p><b>Что закреплено.</b> Числа печатаются в отчёт теста ({@code [C5.6b]}) и проверяются
 * потолком: потолок можно поднять только вместе с объяснением, потому что иначе он перестаёт
 * быть бюджетом и становится описанием текущего поведения. Фикстура и оба сохранения идут в
 * одной транзакции с {@code setRollbackOnly} в конце: замер не оставляет после себя пилотных
 * строк в общей тестовой базе.</p>
 */
@SpringBootTest(classes = Application.class, properties = {
    "spring.jpa.properties.hibernate.session_factory.statement_inspector="
        + "org.ip.config.PilotSecurityQueryMeasurementIT$CountingInspector"})
class PilotSecurityQueryMeasurementIT {

    /** Таблицы решений: гранты, роли и пользователь — то, чем C5 и RLS отвечают на вопрос. */
    private static final List<String> SECURITY_TABLES =
        List.of("access_grant", "app_user", "app_role", "user_role", "role");

    /** Потолок первого сохранения: замер 2026-10-08 даёт меньше; рост требует объяснения. */
    private static final int INSERT_CEILING = 40;

    @MockitoBean
    private DataInitializer demoSeeding;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityDataAccess access;

    @Autowired
    private MetadataDrivenAggregateSaveService aggregates;

    @Autowired
    private AccountingObjectSaveService documents;

    @Autowired
    private AccessGrantRepository grants;

    @Autowired
    private UserRepository users;

    @Autowired
    private RoleRepository roles;

    @Autowired
    private PasswordEncoder passwords;

    @Autowired
    private TransactionTemplate transactions;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void onePilotDocumentSaveHasAMeasuredSecurityQueryCost() {
        transactions.executeWithoutResult(status -> {
            initializer().seedFixture();
            asPilot(() -> {
                Workshop workshop = entityByCode(Workshop.class, "PILOT-A");
                // Фикстура уже завела объект учёта для PILOT-01, а повторный объект того же
                // цеха запрещён доменным правилом: измеряемый документ идёт по PILOT-02.
                Nomenclature nomenclature = entityByCode(Nomenclature.class, "PILOT-02");
                AccountingType fifo = entityByCode(AccountingType.class, "FIFO");

                Counter insert = measure(() -> documents.save(
                    installation("ПРОБА-У-002", workshop),
                    List.of(MetadataDrivenAggregateSaveService.SectionInput.attached(
                        AccountingObjectInstallationItem.class,
                        List.of(installationItem(nomenclature, fifo)))),
                    EventSource.UI));
                report("первичное сохранение документа (installation, одна позиция)", insert);

                Counter update = measure(() -> documents.save(
                    installationByNumber("ПРОБА-У-002"),
                    List.of(MetadataDrivenAggregateSaveService.SectionInput.attached(
                        AccountingObjectInstallationItem.class,
                        List.of(installationItem(nomenclature, fifo)))),
                    EventSource.UI));
                report("повторное сохранение того же документа (update той же позиции)", update);

                assertThat(insert.total())
                    .as("измерение не должно быть вакуумным: сохранение документа делает SQL")
                    .isGreaterThan(0);
                assertThat(insert.securityStatements())
                    .as("решение об операции и построчный доступ не могут стоить ноль запросов")
                    .isGreaterThan(0);
                assertThat(insert.total())
                    .as("потолок сохранения — бюджет, а не описание: рост требует объяснения;"
                        + " фактическое число напечатано в отчёте")
                    .isLessThanOrEqualTo(INSERT_CEILING);
            });
            status.setRollbackOnly();
        });
    }

    // === Сценарий ===

    private AccountingObjectPilotInitializer initializer() {
        return new AccountingObjectPilotInitializer(entityManager, access, aggregates, documents,
            grants, users, roles, passwords, "pilot-fixture-password");
    }

    /** Субъект пилота: тот же вход, что у {@code seedFixture()}, но вокруг измеряемых сохранений. */
    private void asPilot(Runnable action) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext subject = SecurityContextHolder.createEmptyContext();
        subject.setAuthentication(new UsernamePasswordAuthenticationToken(
            AccountingObjectPilotInitializer.PILOT_SUBJECT, "", List.of()));
        SecurityContextHolder.setContext(subject);
        try {
            action.run();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private <T> T entityByCode(Class<T> type, String code) {
        return entityManager.createQuery(
                "select e from " + type.getSimpleName() + " e where e.code = :code", type)
            .setParameter("code", code)
            .getSingleResult();
    }

    /** Документ учёта адресуется номером: у него нет кода словаря. */
    private AccountingObjectInstallation installationByNumber(String number) {
        return entityManager.createQuery("select d from AccountingObjectInstallation d"
                + " where d.number = :number", AccountingObjectInstallation.class)
            .setParameter("number", number)
            .getSingleResult();
    }

    private static AccountingObjectInstallation installation(String number, Workshop workshop) {
        AccountingObjectInstallation installation = new AccountingObjectInstallation();
        installation.setNumber(number);
        installation.setWorkshop(workshop);
        installation.setDate(LocalDateTime.of(2026, 10, 8, 12, 0));
        return installation;
    }

    private static AccountingObjectInstallationItem installationItem(Nomenclature nomenclature,
                                                                     AccountingType type) {
        AccountingObjectInstallationItem item = new AccountingObjectInstallationItem();
        item.setNomenclature(nomenclature);
        item.setAccountingType(type);
        return item;
    }

    // === Замер ===

    private Counter measure(Runnable action) {
        CountingInspector.begin();
        try {
            action.run();
            return CountingInspector.stop();
        } catch (RuntimeException | Error error) {
            CountingInspector.stop();
            throw error;
        }
    }

    private void report(String what, Counter counter) {
        System.out.println("[C5.6b] " + what + ": всего SQL " + counter.total()
            + ", запросов к таблицам решений " + counter.securityStatements()
            + ", из них к access_grant " + counter.statementsMatching("access_grant"));
        TreeMap<String, Integer> repeats = new TreeMap<>();
        for (String statement : counter.statements()) {
            if (!counter.isSecurityStatement(statement)) {
                continue;
            }
            repeats.merge(normalize(statement), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : repeats.entrySet()) {
            System.out.println("[C5.6b]   x" + entry.getValue() + " " + entry.getKey());
        }
    }

    /** Нормализация для группировки повторов: значения параметров замера не различают. */
    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    /**
     * Счётчик для этого контекста: Hibernate держит один {@code StatementInspector}, поэтому
     * инспектор заменён целиком, а не скомпонован. Регистрируется по имени класса (public
     * no-arg конструктор), поэтому доступ к окну — через статический {@link ThreadLocal}.
     */
    public static final class CountingInspector implements StatementInspector {

        private static final ThreadLocal<Counter> CURRENT = new ThreadLocal<>();

        static void begin() {
            CURRENT.set(new Counter());
        }

        static Counter stop() {
            Counter counter = CURRENT.get();
            CURRENT.remove();
            return counter == null ? new Counter() : counter;
        }

        @Override
        public String inspect(String sql) {
            Counter counter = CURRENT.get();
            if (counter != null && sql != null) {
                counter.record(sql);
            }
            return sql;
        }
    }

    /** SQL одного окна измерения. */
    static final class Counter {

        private final List<String> statements = new ArrayList<>();

        void record(String sql) {
            statements.add(sql);
        }

        int total() {
            return statements.size();
        }

        long securityStatements() {
            return statements.stream().filter(this::isSecurityStatement).count();
        }

        long statementsMatching(String table) {
            return statements.stream()
                .filter(statement -> statement.toLowerCase(Locale.ROOT).contains(table))
                .count();
        }

        boolean isSecurityStatement(String sql) {
            String lower = sql.toLowerCase(Locale.ROOT);
            return SECURITY_TABLES.stream().anyMatch(lower::contains);
        }

        List<String> statements() {
            return List.copyOf(statements);
        }
    }
}
