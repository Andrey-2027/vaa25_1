package org.ip.config;

import org.ip.model.Role;
import org.ip.model.User;
import org.ip.repository.RoleRepository;
import org.ip.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
 */
@Component
@Profile("(dev | demo | test) & !prod")
@ConditionalOnProperty(name = "app.demo-data.enabled", havingValue = "true")
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(UserRepository userRepository,
                           RoleRepository roleRepository,
                           PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Транзакция обязательна: без неё findByName возвращает detached-Role,
     * и save(user) с каскадом на роль падает в Hibernate 7
     * ("detached entity with generated id ... uninitialized version").
     */
    @Override
    @Transactional
    public void run(String... args) {
        if (roleRepository.count() == 0) {
            roleRepository.save(new Role("ADMIN"));
            roleRepository.save(new Role("MANAGER"));
            roleRepository.save(new Role("VIEWER"));
        }

        if (userRepository.count() == 0) {
            Role adminRole = roleRepository.findByName("ADMIN").orElseThrow();
            Role managerRole = roleRepository.findByName("MANAGER").orElseThrow();
            Role viewerRole = roleRepository.findByName("VIEWER").orElseThrow();

            User admin = new User("admin", passwordEncoder.encode("admin"));
            admin.addRole(adminRole);
            userRepository.save(admin);

            User manager = new User("manager", passwordEncoder.encode("manager"));
            manager.addRole(managerRole);
            userRepository.save(manager);

            User viewer = new User("viewer", passwordEncoder.encode("viewer"));
            viewer.addRole(viewerRole);
            userRepository.save(viewer);
        }
    }
}
