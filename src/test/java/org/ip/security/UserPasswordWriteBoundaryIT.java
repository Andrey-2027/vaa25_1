package org.ip.security;

import jakarta.persistence.EntityManager;
import org.ip.application.user.UserPasswordChangePermission;
import org.ip.config.DataInitializer;
import org.ip.model.Role;
import org.ip.model.User;
import org.ip.repository.UserRepository;
import org.ipro.data.EntityDataAccess;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsTestFixture;
import org.ipro.rls.c5.C5AccessDeniedException;
import org.ipro.rls.c5.C5PermissionKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C5.8e: хэш пароля не переносится generic-записью. Изменение хэша — предмет именованной операции
 * {@code ACTION:USER:CHANGE_PASSWORD}, и это решение спрашивается на границе canonical write, а не
 * только в {@code UserService} и форме (ADR-0017 §12, ADR-0019 §2 по форме).
 *
 * <p>Проверяется именно <b>чужой канал</b>: субъект с правом изменения строки {@code User}, но без
 * права на смену пароля, пишет мимо {@code UserService} — тем же canonical update, которым пишет
 * любой другой вызывающий. Отказ обязан быть отказом именованной операции, а не молчаливой записью
 * и не «повезло, что форма закрыта». Контроль невакуумности: то же право пускает ту же запись; та
 * же запись без изменения хэша проходит без него; затирание хэша считается изменением.</p>
 *
 * <p>Класс транзакционный: строки грантов и созданные пользователи откатываются вместе с тестом.</p>
 */
@SpringBootTest
@Transactional
class UserPasswordWriteBoundaryIT {

    private static final String PASSWORD_ID = UserPasswordChangePermission.PERMISSION_ID;
    private static final String PASSWORD_KEY = C5PermissionKeys.action(PASSWORD_ID);
    private static final String INITIAL_HASH = "initial-hash-value";
    private static final String NEW_HASH = "changed-hash-value";

    @MockitoBean
    private DataInitializer initializer;

    @Autowired
    private EntityDataAccess access;

    @Autowired
    private AccessGrantRepository grants;

    @Autowired
    private UserRepository users;

    @jakarta.persistence.PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    /** Общий случай: canonical update несёт новый хэш — без операции отказ, с операцией запись. */
    @Test
    void aGenericHashChangeNeedsItsOwnOperation() {
        String targetName = "password-boundary-" + suffix();
        User target = createAsSuperuser(targetName);
        loginAsGenericWriter("password-writer-" + suffix());

        User changed = copyWithPassword(target, NEW_HASH);
        assertThatThrownBy(() -> access.update(User.class, changed))
            .as("право на изменение строки User не открывает смену пароля")
            .isInstanceOf(C5AccessDeniedException.class)
            .satisfies(thrown -> assertThat(((C5AccessDeniedException) thrown).decision().key())
                .isEqualTo(PASSWORD_KEY));
        assertThat(storedHash(targetName))
            .as("отказ не оставляет частичной записи")
            .isEqualTo(INITIAL_HASH);

        RlsTestFixture.grantC5Action(grants, currentUser(), PASSWORD_ID);
        access.update(User.class, copyWithPassword(freshCopy(targetName), NEW_HASH));

        assertThat(storedHash(targetName))
            .as("то же право пускает ту же запись — отказ не был запретом канала")
            .isEqualTo(NEW_HASH);
    }

    /** Создание учётной записи включает пароль: тот же хэш на новой строке требует ту же операцию. */
    @Test
    void creatingAUserWithAPasswordNeedsTheSameOperation() {
        String writer = loginAsGenericWriter("password-create-" + suffix());
        String createdName = "password-created-" + suffix();

        assertThatThrownBy(() -> access.create(User.class, new User(createdName, INITIAL_HASH)))
            .as("непустой хэш при создании — это тоже смена пароля")
            .isInstanceOf(C5AccessDeniedException.class)
            .satisfies(thrown -> assertThat(((C5AccessDeniedException) thrown).decision().key())
                .isEqualTo(PASSWORD_KEY));
        assertThat(users.findByUsername(createdName)).isEmpty();

        RlsTestFixture.grantC5Action(grants, writer, PASSWORD_ID);
        access.create(User.class, new User(createdName, INITIAL_HASH));

        assertThat(storedHash(createdName)).isEqualTo(INITIAL_HASH);
    }

    /** Запись, которая хэш не трогает, правилом не затрагивается — иначе оно блокировало бы всё. */
    @Test
    void aWriteThatDoesNotTouchTheHashIsNotBlocked() {
        String targetName = "password-untouched-" + suffix();
        createAsSuperuser(targetName);
        loginAsGenericWriter("password-untouched-writer-" + suffix());

        User copy = freshCopy(targetName);
        copy.setEnabled(false);
        access.update(User.class, copy);

        entityManager.flush();
        entityManager.clear();
        assertThat(users.findByUsername(targetName).orElseThrow().isEnabled())
            .as("выключение учётной записи — обычное изменение строки, не смена пароля")
            .isFalse();
        assertThat(storedHash(targetName)).isEqualTo(INITIAL_HASH);
    }

    /** Затирание хэша — изменение, а не «пустое значит не менять»: без операции оно отклоняется. */
    @Test
    void erasingTheHashCountsAsAChange() {
        String targetName = "password-erase-" + suffix();
        User target = createAsSuperuser(targetName);
        loginAsGenericWriter("password-erase-writer-" + suffix());

        assertThatThrownBy(() -> access.update(User.class, copyWithPassword(target, null)))
            .isInstanceOf(C5AccessDeniedException.class)
            .satisfies(thrown -> assertThat(((C5AccessDeniedException) thrown).decision().key())
                .isEqualTo(PASSWORD_KEY));
        assertThat(storedHash(targetName)).isEqualTo(INITIAL_HASH);
    }

    // === Фикстура ===

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** Строка создаётся суперпользователем фикстуры: у него есть все зарегистрированные операции. */
    private User createAsSuperuser(String username) {
        return RlsTestFixture.callAsSuperuserWithRead(grants, new Class<?>[]{User.class},
            () -> access.create(User.class, new User(username, INITIAL_HASH)));
    }

    /**
     * Субъект с правом изменения строки {@code User} и без права на пароль: ровно тот вызывающий,
     * которого закрывает правило. Вход в систему называет его же именем.
     */
    private String loginAsGenericWriter(String username) {
        RlsTestFixture.grantC5EntityRead(grants, username, User.class);
        RlsTestFixture.grantC5EntityOperations(grants, username, User.class);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
        return username;
    }

    private String currentUser() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    /** Предсостояние из базы: копия строится по нему, а не по живому экземпляру сессии. */
    private User freshCopy(String username) {
        entityManager.flush();
        entityManager.clear();
        return users.findByUsername(username).orElseThrow();
    }

    /**
     * Отсоединённая копия строки с новым хэшем: живой экземпляр сессии не годится — правило
     * сравнивает предсостояние с целью, и подмена одного другим скрыла бы изменение.
     */
    private User copyWithPassword(User source, String hash) {
        User copy = new User(source.getUsername(), hash);
        copy.setId(source.getId());
        copy.setVersion(source.getVersion());
        copy.setEnabled(source.isEnabled());
        for (Role role : source.getRoles()) {
            copy.addRole(role);
        }
        return copy;
    }

    private String storedHash(String username) {
        entityManager.flush();
        entityManager.clear();
        return users.findByUsername(username).orElseThrow().getPassword();
    }
}
