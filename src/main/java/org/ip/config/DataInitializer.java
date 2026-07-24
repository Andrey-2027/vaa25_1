package org.ip.config;

import org.ip.model.Role;
import org.ip.model.User;
import org.ip.repository.RoleRepository;
import org.ip.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
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

    @Override
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
