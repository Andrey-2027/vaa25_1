package org.ip.config;

import jakarta.persistence.EntityManager;
import org.ip.application.register.AccountingObjectSaveService;
import org.ip.application.user.UserPasswordChangePermission;
import org.ip.model.*;
import org.ip.repository.RoleRepository;
import org.ip.repository.UserRepository;
import org.ipro.crud.MetadataDrivenAggregateSaveService;
import org.ipro.data.EntityDataAccess;
import org.ipro.events.EventSource;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.c5.C5PermissionKeys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Explicit local pilot fixture, committed atomically through the ordinary write boundaries.
 *
 * <p><b>Прямых записей у фикстуры нет.</b> Роль ADMIN и пользователь {@link #PILOT_SUBJECT} —
 * governed-типы финальной границы записи (ADR-0017 §9), поэтому repository-{@code save} мимо
 * preflight отказал бы при вооружённой границе. Фикстура объявляет собственный названный
 * субъект, получает ровно нужные права (read-ключ и ключ операций на два своих типа) до первой
 * записи и пишет через {@link EntityDataAccess} — тем же путём, которым писал бы человек.
 * Bypass прикладному коду недоступен: {@code RlsBypassScope} — закрытый перечень системных
 * причин, а {@code RlsContext.runAsSystem} для приложения бросает исключение.</p>
 */
@Component
@Profile("accounting-pilot & !prod")
@ConditionalOnProperty(name = "app.accounting-pilot.seed", havingValue = "true")
public class AccountingObjectPilotInitializer implements CommandLineRunner {

    /**
     * Субъект фикстуры. Назван константой, потому что он называет себя в собственных правах:
     * строка в {@code access_grant} без объяснения читалась бы как чужая, а так она привязана
     * к единственному месту, которое её создаёт.
     */
    static final String PILOT_SUBJECT = "accounting-pilot";

    private static final List<Class<?>> PILOT_TYPES = List.of(Workshop.class, Nomenclature.class,
        UnitOfMeasurement.class, AccountingType.class, AccountingObjectInstallation.class,
        AccountingObjectCorrection.class, NomenclatureAccountingObject.class, NomenclaturePrice.class);
    // The existing nomenclature card reads these references and sections while opening.
    private static final List<Class<?>> NOMENCLATURE_READ_TYPES = List.of(GroupNom.class,
        AttributeType.class, AttributeValue.class, NomAttributeValue.class, NomSklAttribute.class);
    // Типы, которые фикстура заводит сама: роль ADMIN и входной пользователь пилота.
    private static final List<Class<?>> SEEDED_ACCOUNT_TYPES = List.of(Role.class, User.class);

    private final EntityManager em;
    private final EntityDataAccess access;
    private final MetadataDrivenAggregateSaveService aggregates;
    private final AccountingObjectSaveService documents;
    private final AccessGrantRepository grants;
    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwords;
    private final String initialPassword;

    public AccountingObjectPilotInitializer(EntityManager em, EntityDataAccess access,
            MetadataDrivenAggregateSaveService aggregates, AccountingObjectSaveService documents,
            AccessGrantRepository grants, UserRepository users, RoleRepository roles,
            PasswordEncoder passwords,
            @Value("${app.accounting-pilot.password}") String initialPassword) {
        this.em = em; this.access = access; this.aggregates = aggregates; this.documents = documents;
        this.grants = grants; this.users = users; this.roles = roles; this.passwords = passwords;
        this.initialPassword = initialPassword;
    }

    @Override @Transactional
    public void run(String... args) {
        String database = (String) em.createNativeQuery("select current_database()", String.class).getSingleResult();
        if (!database.matches("accounting_object_pilot_[a-z0-9_]+")) {
            throw new IllegalStateException("Pilot seed requires an isolated accounting_object_pilot_* database");
        }
        if (users.findByUsername(PILOT_SUBJECT).isPresent()) return;
        seedFixture();
    }

    /**
     * Тело наполнения: права, субъект, записи.
     *
     * <p>Выделено из {@link #run}, потому что production-запуск защищён требованием отдельной
     * базы, а границу записи нужно проверять на том же коде, что идёт и в пилоте, — с тем же
     * субъектом и теми же правами, а не на копии последовательности в тесте.</p>
     *
     * <p>Права выдаются до первой записи и в той же транзакции: они — часть подготовки
     * фикстуры, и либо есть вместе с данными, либо нет вовсе.</p>
     */
    void seedFixture() {
        grant("*");
        for (Class<?> type : PILOT_TYPES) grant("ENTITY:" + type.getName());
        for (Class<?> type : NOMENCLATURE_READ_TYPES) grantRead(type);
        // ADR-0017: право на операцию — отдельное глобальное право, а не следствие read-ключа
        // и тем более не следствие построчного "*". Без этих строк пилотная установка и
        // корректировка откажут на первой же записи — предсказание из evidence C5.4.
        for (Class<?> type : PILOT_TYPES) grantOperation(type);
        // Роль и пользователь фикстуры пишутся canonical path, поэтому им нужны те же два
        // права: read-ключ (чтение ADMIN и проверка существования) и ключ операций.
        for (Class<?> type : SEEDED_ACCOUNT_TYPES) {
            grantRead(type);
            grantOperation(type);
        }
        // Пароль — отдельная операция владельца, а не свойство generic-записи: canonical create
        // учётной записи с паролем проходит только с этой строкой, поэтому она выдана фикстуре
        // явно и заранее — как и остальные её права.
        grantAction(UserPasswordChangePermission.PERMISSION_ID);
        grants.flush();

        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext subject = SecurityContextHolder.createEmptyContext();
        subject.setAuthentication(new UsernamePasswordAuthenticationToken(PILOT_SUBJECT, "", List.of()));
        SecurityContextHolder.setContext(subject);
        try {
            Role role = roles.findByName("ADMIN").orElse(null);
            if (role == null) {
                role = access.create(Role.class, new Role("ADMIN"));
            }
            User user = new User(PILOT_SUBJECT, passwords.encode(initialPassword));
            user.addRole(role);
            access.create(User.class, user);

            Workshop a = access.create(Workshop.class, new Workshop("PILOT-A", "Пилот — цех А"));
            access.create(Workshop.class, new Workshop("PILOT-B", "Пилот — цех Б"));
            var unit = access.create(UnitOfMeasurement.class, new UnitOfMeasurement("шт", "Штука", "PILOT-PC"));
            var n = aggregates.save(new Nomenclature("PILOT-01", "Пилот — изделие 1", unit),
                List.of(), EventSource.UI).aggregate();
            aggregates.save(new Nomenclature("PILOT-02", "Пилот — изделие 2", unit), List.of(), EventSource.UI);
            var fifo = access.create(AccountingType.class, new AccountingType("FIFO", "ФИФО"));
            var lifo = access.create(AccountingType.class, new AccountingType("LIFO", "ЛИФО"));
            access.create(AccountingType.class, new AccountingType("SKE", "СКЕ"));
            var installation = new AccountingObjectInstallation();
            installation.setNumber("ПРОБА-У-001"); installation.setWorkshop(a);
            installation.setDate(LocalDateTime.of(2026, 10, 1, 10, 0));
            var initial = new AccountingObjectInstallationItem(); initial.setNomenclature(n); initial.setAccountingType(fifo);
            documents.save(installation, List.of(MetadataDrivenAggregateSaveService.SectionInput.attached(
                AccountingObjectInstallationItem.class, List.of(initial))), EventSource.UI);
            var correction = new AccountingObjectCorrection();
            correction.setNumber("ПРОБА-К-001"); correction.setWorkshop(a);
            correction.setDate(LocalDateTime.of(2026, 10, 2, 10, 0));
            var change = new AccountingObjectCorrectionItem(); change.setNomenclature(n); change.setAccountingType(lifo);
            documents.save(correction, List.of(MetadataDrivenAggregateSaveService.SectionInput.attached(
                AccountingObjectCorrectionItem.class, List.of(change))), EventSource.UI);
        } finally { SecurityContextHolder.setContext(previous); }
    }

    /** Право на именованную операцию: глобальная строка, где участвует только «выполнение». */
    private void grantAction(String permissionId) {
        var grant = new AccessGrant(); grant.setSubjectType(AccessGrant.SubjectType.USER);
        grant.setSubjectKey(PILOT_SUBJECT);
        grant.setDimension(C5PermissionKeys.action(permissionId));
        grant.setDimensionValueId(null);
        grant.setCanUpdate(true); grants.save(grant);
    }

    /** Право на операцию: глобальная строка, где canRead не участвует (ADR-0017). */
    private void grantOperation(Class<?> type) {
        var grant = new AccessGrant(); grant.setSubjectType(AccessGrant.SubjectType.USER);
        grant.setSubjectKey(PILOT_SUBJECT);
        grant.setDimension(C5PermissionKeys.entityOperations(type));
        grant.setDimensionValueId(null);
        grant.setCanUpdate(true); grant.setCanDelete(true); grants.save(grant);
    }

    private void grant(String dimension) {
        var grant = new AccessGrant(); grant.setSubjectType(AccessGrant.SubjectType.USER);
        grant.setSubjectKey(PILOT_SUBJECT); grant.setDimension(dimension);
        grant.setCanRead(true); grant.setCanUpdate(true); grant.setCanDelete(true); grants.save(grant);
    }
    private void grantRead(Class<?> type) {
        var grant = new AccessGrant(); grant.setSubjectType(AccessGrant.SubjectType.USER);
        grant.setSubjectKey(PILOT_SUBJECT);
        grant.setDimension(C5PermissionKeys.entityRead(type));
        grant.setCanRead(true); grants.save(grant);
    }
}
