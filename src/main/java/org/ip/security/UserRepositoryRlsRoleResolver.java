package org.ip.security;

import org.ip.model.Role;
import org.ip.repository.UserRepository;
import org.ip.rls.RlsRoleResolver;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Мост между org.ip.rls (не знает про User/Role этого приложения) и реальной ролевой
 * моделью — единственное место в приложении, которое реализует RlsRoleResolver.
 */
@Component
public class UserRepositoryRlsRoleResolver implements RlsRoleResolver {

    private final UserRepository userRepository;

    public UserRepositoryRlsRoleResolver(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public List<String> rolesOf(String username) {
        return userRepository.findByUsername(username)
            .map(user -> user.getRoles().stream().map(Role::getName).toList())
            .orElse(List.of());
    }
}