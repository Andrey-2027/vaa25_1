package org.ip.config;

import jakarta.persistence.EntityManager;
import org.ip.Application;
import org.ip.model.Role;
import org.ip.model.UnitOfMeasurement;
import org.ip.model.User;
import org.ip.repository.RoleRepository;
import org.ip.repository.UserRepository;
import org.ipro.data.EntityDataAccess;
import org.ipro.rls.RlsAccessDeniedException;
import org.ipro.rls.RlsDimensionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C5.6 (ADR-0017 §9): первое место прямой записи, переведённое на canonical path, и граница,
 * вооружённая ровно для его типов.
 *
 * <p>Предмет — демо-наполнение {@link DataInitializer}: роли ADMIN/MANAGER/VIEWER и пользователи
 * admin/manager/viewer. Прежде это была прямая запись через repository, то есть при вооружённой
 * границе демо-наполнение отказывало на первом же {@code save}. Здесь граница вооружена
 * пошагово — только для {@code Role} и {@code User} ({@code rls.write-boundary.enforced-types}),
 * а seeding идёт canonical path от собственного названного субъекта. Значит, тест проверяет не
 * «флаг включён», а что <b>включение границы для этих типов ничего не сломало</b>: данные на
 * месте, а обходной вход закрыт.</p>
 *
 * <p>Границы утверждения важны: вооружены ровно два типа. Неназванный тип с canonical write
 * handle остаётся снятым — это отдельный шаг миграции, а не забытый, и он виден в снятии
 * {@code isWriteGoverned} у {@link UnitOfMeasurement}.</p>
 */
@SpringBootTest(classes = Application.class, properties = {
    "spring.profiles.active=test",
    "app.demo-data.enabled=true",
    "rls.write-boundary.enforced-types=org.ip.model.Role,org.ip.model.User"})
class DemoSeedingWriteBoundaryIT {

    @Autowired
    private RoleRepository roles;

    @Autowired
    private UserRepository users;

    @Autowired
    private EntityDataAccess access;

    @Autowired
    private RlsDimensionRegistry registry;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactions;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** Демо-наполнение прошло на старте, хотя граница для его типов вооружена. */
    @Test
    void demoSeedingWroteThroughTheArmedBoundary() {
        assertThat(roles.count()).isEqualTo(3);
        assertThat(users.count()).isEqualTo(3);
        assertThat(roles.findByName("ADMIN")).isPresent();
        assertThat(users.findByUsername("admin")).isPresent();
    }

    /** Вооружены ровно названные типы: пошаговость видна, а не подразумевается. */
    @Test
    void onlyTheNamedTypesAreArmed() {
        assertThat(registry.isWriteGoverned(Role.class)).isTrue();
        assertThat(registry.isWriteGoverned(User.class)).isTrue();
        assertThat(registry.isWriteGoverned(UnitOfMeasurement.class))
            .as("неназванный тип остаётся снятым: это следующий шаг миграции, а не забытый")
            .isFalse();
    }

    /** Прямая запись роли мимо canonical path — отказ на flush, а не тихая запись. */
    @Test
    void aDirectRoleWriteIsDeniedAtFlush() {
        String name = "BYPASS-" + suffix();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
            entityManager.persist(new Role(name))))
            .isInstanceOf(RlsAccessDeniedException.class)
            .hasMessageContaining("canonical write preflight")
            .hasMessageContaining(Role.class.getName());

        assertThat(roles.findByName(name)).isEmpty();
    }

    /** То же для пользователя: второй названный тип ведёт себя одинаково. */
    @Test
    void aDirectUserWriteIsDeniedAtFlush() {
        String username = "bypass-" + suffix();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
            entityManager.persist(new User(username, "n/a"))))
            .isInstanceOf(RlsAccessDeniedException.class)
            .hasMessageContaining("canonical write preflight")
            .hasMessageContaining(User.class.getName());

        assertThat(users.findByUsername(username)).isEmpty();
    }

    /**
     * Контроль не-вакуумности: canonical path для вооружённого типа остался открыт и после
     * старта. Субъект — тот же, что у seeding: права уже выданы и переживают старт, потому что
     * это обычные строки {@code access_grant}, а не временное состояние контекста.
     */
    @Test
    void theCanonicalPathRemainsOpenForTheSeedingSubject() {
        String name = "ROLE-EXTRA-" + suffix();
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext subject = SecurityContextHolder.createEmptyContext();
        subject.setAuthentication(new UsernamePasswordAuthenticationToken(
            DataInitializer.SEED_SUBJECT, "", List.of()));
        SecurityContextHolder.setContext(subject);
        try {
            Role created = access.create(Role.class, new Role(name));

            assertThat(created.getId()).isNotNull();
            assertThat(roles.findByName(name)).isPresent();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
