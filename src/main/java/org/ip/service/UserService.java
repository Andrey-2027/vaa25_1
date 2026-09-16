package org.ip.service;

import org.ipro.crud.BaseService;
import org.ipro.crud.ValidationException;
import org.ipro.data.CanonicalEntityService;
import org.ipro.data.EntityDataAccessResolver;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.telemetry.api.Measured;

import org.ip.model.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Пользователь как CRUD-путь канонической границы плюс одно предметное правило:
 * нормализация пароля.
 *
 * <p>{@code rawPassword} (см. {@link User}) — единственный канал, по которому форма передаёт
 * сюда новый пароль в открытом виде. {@code save}/{@code create}/{@code update} хэшируют его
 * и обнуляют; пустой {@code rawPassword} на существующем пользователе означает «не менять
 * пароль», а на новом — ошибку ввода.</p>
 *
 * <p>C4.6 волна E: класс больше не наследует compatibility base. Чтение и запись идут через
 * canonical handle ({@link EntityDataAccessResolver}): capability-граница, ранний RLS,
 * валидация, нумерация, lifecycle и события применяются тем же pipeline, что и у типов без
 * собственного сервиса. Нормализация выполняется <b>до</b> делегирования: хэш — часть
 * состояния, которое валидируется ({@code password} объявлен {@code @NotBlank}), поэтому
 * порядок «сначала нормализация, затем граница» здесь обязателен.</p>
 */
@Measured
@Service
public class UserService implements BaseService<User, Long> {

    private final CanonicalEntityService<User> canonical;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    public UserService(EntityDataAccessResolver dataAccessResolver, PasswordEncoder passwordEncoder) {
        Objects.requireNonNull(dataAccessResolver, "dataAccessResolver must not be null");
        this.canonical = canonicalHandle(dataAccessResolver);
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder,
            "passwordEncoder must not be null");
    }

    /**
     * Сборка с явным canonical handle — только для тестов, где полный контекст не нужен.
     * Намеренно не {@code public}: Spring должен видеть ровно один кандидат на
     * autowiring, иначе он не выберет конструктор вообще.
     */
    UserService(CanonicalEntityService<User> canonical, PasswordEncoder passwordEncoder) {
        this.canonical = Objects.requireNonNull(canonical, "canonical must not be null");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder,
            "passwordEncoder must not be null");
    }

    /**
     * Handle нужен как класс, а не как {@link BaseService}: у интерфейса методы
     * {@code delete(Long)} (из {@code CrudService}) и {@code delete(ID)} (из {@code BaseService})
     * при {@code ID = Long} неразличимы, и вызов через интерфейс не компилируется.
     */
    private static CanonicalEntityService<User> canonicalHandle(EntityDataAccessResolver resolver) {
        BaseService<User, Long> handle = resolver.<User, Long>findService(User.class)
            .orElseThrow(() -> new IllegalStateException(
                "User не имеет canonical data handle: " + resolver.resolutionReason(User.class)));
        // findService всегда строит именно canonical service: кастомный policy подставляет
        // свой EntityDataAccess внутрь того же handle, а не отдельный сервис.
        @SuppressWarnings("unchecked")
        CanonicalEntityService<User> canonical = (CanonicalEntityService<User>) handle;
        return canonical;
    }

    @Override
    public User save(User entity) {
        applyRawPasswordIfPresent(entity);
        return canonical.save(entity);
    }

    @Override
    public User create(User entity) {
        applyRawPasswordIfPresent(entity);
        return canonical.create(entity);
    }

    @Override
    public User update(User entity) {
        applyRawPasswordIfPresent(entity);
        return canonical.update(entity);
    }

    @Override
    public void delete(Long id) {
        canonical.delete(id);
    }

    @Override
    public Optional<User> findById(Long id) {
        return canonical.findById(id);
    }

    @Override
    public List<User> findAll() {
        return canonical.findAll();
    }

    @Override
    public Page<User> findAll(Pageable pageable) {
        return canonical.findAll(pageable);
    }

    @Override
    public Page<User> findAll(Specification<User> spec, Pageable pageable) {
        return canonical.findAll(spec, pageable);
    }

    @Override
    public Page<User> findAll(Specification<User> spec, Pageable pageable,
                              Collection<String> fetchPaths) {
        return canonical.findAll(spec, pageable, fetchPaths);
    }

    @Override
    public Page<User> findAllByScenario(FetchScenario scenario, Specification<User> spec,
                                        Pageable pageable, Collection<String> additionalFetchPaths) {
        return canonical.findAllByScenario(scenario, spec, pageable, additionalFetchPaths);
    }

    @Override
    public List<User> search(String term) {
        return canonical.search(term);
    }

    @Override
    public Page<User> search(String term, Pageable pageable) {
        return canonical.search(term, pageable);
    }

    @Override
    public Number sum(String fieldName, Specification<User> spec) {
        return canonical.sum(fieldName, spec);
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
