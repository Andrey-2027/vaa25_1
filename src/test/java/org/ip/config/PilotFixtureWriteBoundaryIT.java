package org.ip.config;

import jakarta.persistence.EntityManager;
import org.ip.Application;
import org.ip.application.register.AccountingObjectSaveService;
import org.ip.model.AttributeType;
import org.ip.model.Role;
import org.ip.model.User;
import org.ip.repository.RoleRepository;
import org.ip.repository.UserRepository;
import org.ipro.crud.MetadataDrivenAggregateSaveService;
import org.ipro.data.EntityDataAccess;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsDimensionRegistry;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C5.6 (ADR-0017 §9): пилотная фикстура — второе (и последнее в production-коде) место прямой
 * записи {@code Role}/{@code User}, переведённое на canonical path, и граница, вооружённая ровно
 * для его типов.
 *
 * <p>Предмет — {@link AccountingObjectPilotInitializer}: роль ADMIN и входной пользователь
 * {@code accounting-pilot}. Прежняя фикстура писала их repository-{@code save} мимо preflight,
 * поэтому при вооружённой границе пилот отказал бы на первой же записи. Здесь граница вооружена
 * пошагово ({@code rls.write-boundary.enforced-types}) только для {@code Role} и {@code User}, а
 * наполнение идёт canonical path от собственного названного субъекта с явными правами. Значит,
 * тест проверяет не «флаг включён», а что включение границы для этих типов ничего не сломало.</p>
 *
 * <p><b>Почему тело вызывается напрямую.</b> {@code run()} пилота защищён требованием отдельной
 * базы {@code accounting_object_pilot_*}: это production-предохранитель, и тест его не ослабляет.
 * Поэтому фикстура выделила тело ({@code seedFixture()}), а тест собирает инициализатор из
 * настоящих бинов контекста и вызывает то же тело — с тем же субъектом и теми же правами.</p>
 */
@SpringBootTest(classes = Application.class, properties = {
    "rls.write-boundary.enforced-types=org.ip.model.Role,org.ip.model.User"})
class PilotFixtureWriteBoundaryIT {

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
    private RlsDimensionRegistry registry;

    @Autowired
    private TransactionTemplate transactions;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** Фикстура целиком проходит canonical path: ADMIN и входной пользователь на месте. */
    @Test
    void thePilotFixtureSeedsItsAccountsThroughTheArmedBoundary() {
        transactions.executeWithoutResult(status -> {
            initializer().seedFixture();

            Role admin = roles.findByName("ADMIN").orElseThrow();
            User pilot = users.findByUsername(AccountingObjectPilotInitializer.PILOT_SUBJECT)
                .orElseThrow();
            assertThat(pilot.getRoles()).extracting(Role::getName).containsExactly("ADMIN");
            assertThat(admin.getId()).isNotNull();

            status.setRollbackOnly();
        });
    }

    /** Вооружены ровно названные типы: остальные сняты и ждут своей очереди. */
    @Test
    void onlyTheNamedTypesAreArmed() {
        assertThat(registry.isWriteGoverned(Role.class)).isTrue();
        assertThat(registry.isWriteGoverned(User.class)).isTrue();
        assertThat(registry.isWriteGoverned(AttributeType.class))
            .as("неназванный тип остаётся снятым: это следующий шаг миграции, а не забытый")
            .isFalse();
    }

    /**
     * Контроль не-вакуумности: после наполнения canonical path для вооружённого типа остаётся
     * открыт для субъекта пилота — права переживают запуск, потому что это обычные строки
     * {@code access_grant}, а не состояние контекста.
     */
    @Test
    void theCanonicalPathRemainsOpenForThePilotSubject() {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext subject = SecurityContextHolder.createEmptyContext();
        subject.setAuthentication(new UsernamePasswordAuthenticationToken(
            AccountingObjectPilotInitializer.PILOT_SUBJECT, "", List.of()));
        SecurityContextHolder.setContext(subject);
        try {
            transactions.executeWithoutResult(status -> {
                initializer().seedFixture();

                Role extra = access.create(Role.class, new Role("PILOT-EXTRA"));
                assertThat(extra.getId()).isNotNull();
                assertThat(roles.findByName("PILOT-EXTRA")).isPresent();

                status.setRollbackOnly();
            });
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private AccountingObjectPilotInitializer initializer() {
        return new AccountingObjectPilotInitializer(entityManager, access, aggregates, documents,
            grants, users, roles, passwords, "pilot-fixture-password");
    }
}
