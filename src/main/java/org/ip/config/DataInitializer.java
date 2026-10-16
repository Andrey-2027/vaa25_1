package org.ip.config;

import org.ip.application.user.UserPasswordChangePermission;
import org.ip.model.Role;
import org.ip.model.User;
import org.ip.repository.RoleRepository;
import org.ip.repository.UserRepository;
import org.ipro.data.EntityDataAccess;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.c5.C5PermissionKeys;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Demo-наполнение (роли ADMIN/MANAGER/VIEWER, пользователи admin/manager/viewer
 * со статическими паролями) — только для dev/demo/test сред (см. DAC-19).
 *
 * <p>Условия включения (оба обязаны выполняться):
 * <ul>
 *   <li>активен профиль {@code dev}, {@code demo} или {@code test}, но не {@code prod};</li>
 *   <li>{@code app.demo-data.enabled=true} (по умолчанию {@code false}).</li>
 * </ul>
 * Production/default запуск бин не регистрирует. Повторный запуск идемпотентен
 * (сидит только при пустых таблицах).</p>
 *
 * <p><b>Наполнение идёт canonical path, а не в обход границы записи.</b> Роли и пользователи —
 * типы с canonical write handle, поэтому их прямая запись мимо preflight — ровно тот вход,
 * который закрывает финальная граница (ADR-0017 §9). Bypass здесь недоступен: {@code
 * RlsBypassScope} — закрытый перечень системных причин, а публичный {@code RlsContext.runAsSystem}
 * для приложения намеренно бросает исключение. Поэтому seeding объявляет собственный субъект
 * ({@link #SEED_SUBJECT}), получает ровно те права, которые ему нужны (read + операции на двух
 * своих типах), и пишет через {@link EntityDataAccess} — тем же путём, которым писал бы
 * человек. Права выданы явно и до записи, а не «потому что инициализатор свой».</p>
 */
@Component
@Profile("(dev | demo | test) & !prod")
@ConditionalOnProperty(name = "app.demo-data.enabled", havingValue = "true")
public class DataInitializer implements CommandLineRunner {

    /**
     * Субъект демо-наполнения. Назван константой, потому что он называет себя в выданных
     * правах: строка в {@code access_grant} без объяснения читалась бы как чужая, а так она
     * привязана к единственному месту, которое её создаёт.
     */
    static final String SEED_SUBJECT = "demo-data-initializer";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final EntityDataAccess access;
    private final AccessGrantRepository grants;

    public DataInitializer(UserRepository userRepository,
                           RoleRepository roleRepository,
                           PasswordEncoder passwordEncoder,
                           EntityDataAccess access,
                           AccessGrantRepository grants) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.access = access;
        this.grants = grants;
    }

    /**
     * Транзакция обязательна: без неё findByName возвращает detached-Role,
     * и save(user) с каскадом на роль падает в Hibernate 7
     * ("detached entity with generated id ... uninitialized version").
     *
     * <p>Права субъекта пишутся в той же транзакции до первой записи: они — часть подготовки
     * seeding, и либо есть вместе с данными, либо нет вовсе.</p>
     */
    @Override
    @Transactional
    public void run(String... args) {
        boolean rolesMissing = roleRepository.count() == 0;
        boolean usersMissing = userRepository.count() == 0;
        if (!rolesMissing && !usersMissing) {
            return;
        }
        grantSeedRights();
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext subject = SecurityContextHolder.createEmptyContext();
        subject.setAuthentication(new UsernamePasswordAuthenticationToken(
            SEED_SUBJECT, "", List.of()));
        SecurityContextHolder.setContext(subject);
        try {
            if (rolesMissing) {
                access.create(Role.class, new Role("ADMIN"));
                access.create(Role.class, new Role("MANAGER"));
                access.create(Role.class, new Role("VIEWER"));
            }

            if (usersMissing) {
                Role adminRole = roleRepository.findByName("ADMIN").orElseThrow();
                Role managerRole = roleRepository.findByName("MANAGER").orElseThrow();
                Role viewerRole = roleRepository.findByName("VIEWER").orElseThrow();

                User admin = new User("admin", passwordEncoder.encode("admin"));
                admin.addRole(adminRole);
                access.create(User.class, admin);

                User manager = new User("manager", passwordEncoder.encode("manager"));
                manager.addRole(managerRole);
                access.create(User.class, manager);

                User viewer = new User("viewer", passwordEncoder.encode("viewer"));
                viewer.addRole(viewerRole);
                access.create(User.class, viewer);
            }
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    /**
     * Права субъекта seeding: read и операции на двух типах, которые он создаёт, и операция смены
     * пароля. Операция — отдельное глобальное право (ADR-0017), поэтому она выдана строкой на ключ
     * операций, а не следствием построчного {@code "*"}. Пароль — тем более отдельное право:
     * хэш не переносится generic-записью ({@link UserPasswordChangePermission}), поэтому seeding
     * создаёт учётные записи с паролем только потому, что эта строка выдана ему явно и заранее.
     */
    private void grantSeedRights() {
        for (Class<?> type : List.of(Role.class, User.class)) {
            AccessGrant read = new AccessGrant();
            read.setSubjectType(AccessGrant.SubjectType.USER);
            read.setSubjectKey(SEED_SUBJECT);
            read.setDimension(C5PermissionKeys.entityRead(type));
            read.setDimensionValueId(null);
            read.setCanRead(true);
            read.setCanUpdate(true);
            read.setCanDelete(true);
            grants.save(read);
        }

        AccessGrant password = new AccessGrant();
        password.setSubjectType(AccessGrant.SubjectType.USER);
        password.setSubjectKey(SEED_SUBJECT);
        password.setDimension(C5PermissionKeys.action(UserPasswordChangePermission.PERMISSION_ID));
        password.setDimensionValueId(null);
        password.setCanRead(false);
        password.setCanUpdate(true);
        grants.save(password);

        grants.flush();
    }
}
