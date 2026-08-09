package org.ip.service;

import jakarta.validation.Validator;
import org.ip.model.User;
import org.ip.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * rawPassword (см. User) — единственный канал, по которому UserItemForm передаёт сюда новый
 * пароль в открытом виде. save()/update() сами решают, хэшировать его или оставить старый
 * хэш нетронутым (rawPassword == null/blank — пользователь оставил поле пустым, значит,
 * пароль не меняется).
 */
@Service
public class UserService extends AbstractBaseService<User, Long> {

    private final UserRepository repository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository repository, Validator validator, PasswordEncoder passwordEncoder) {
        super(repository, validator);
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public List<User> search(String term) {
        if (term == null || term.isEmpty()) {
            return findAll();
        }
        String lower = term.toLowerCase();
        return findAll().stream()
            .filter(u -> u.getUsername().toLowerCase().contains(lower))
            .toList();
    }

    @Override
    public User save(User entity) {
        applyRawPasswordIfPresent(entity);
        return super.save(entity);
    }

    @Override
    public User create(User entity) {
        applyRawPasswordIfPresent(entity);
        return super.create(entity);
    }

    @Override
    public User update(User entity) {
        applyRawPasswordIfPresent(entity);
        return super.update(entity);
    }

    /**
     * Новый пользователь (id == null) — пароль обязателен. Существующий — пустой rawPassword
     * означает "не менять", а не "поставить пустой пароль".
     */
    private void applyRawPasswordIfPresent(User entity) {
        String rawPassword = entity.getRawPassword();
        boolean isNew = entity.getId() == null;

        if (rawPassword == null || rawPassword.isBlank()) {
            if (isNew) {
                throw new ValidationException("Укажите пароль для нового пользователя");
            }
            return; // редактирование без смены пароля — оставляем текущий хэш как есть
        }

        entity.setPassword(passwordEncoder.encode(rawPassword));
        entity.setRawPassword(null);
    }
}
