package org.ip.service;

import org.ip.persistence.user.PersistedUserState;
import org.ip.application.user.UserPasswordChangePermission;
import org.ip.application.user.UserRoleAssignmentPermission;
import org.ip.model.Role;
import org.ipro.crud.BaseService;
import org.ipro.crud.ValidationException;
import org.ipro.data.CanonicalEntityService;
import org.ipro.data.EntityDataAccessResolver;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.telemetry.api.Measured;

import org.ip.model.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Пользователь как CRUD-путь канонической границы плюс два предметных правила: нормализация
 * пароля и административная авторизация (роли, пароль).
 *
 * <p>{@code rawPassword} (см. {@link User}) — единственный канал, по которому форма передаёт
 * сюда новый пароль в открытом виде. {@code save}/{@code create}/{@code update} хэшируют его
 * и обнуляют; пустой {@code rawPassword} на существующем пользователе означает «не менять
 * пароль», а на новом — ошибку ввода.</p>
 *
 * <p><b>Право проверяется здесь, а не только в форме</b> (ADR-0017 §12). Назначение ролей
 * ({@link UserRoleAssignmentPermission}) и смена пароля ({@link UserPasswordChangePermission}) —
 * отдельные определения владельца, и вызов сервиса спрашивает их сам: форма — один из вызовов,
 * а не граница. Правила:</p>
 * <ul>
 *   <li>пароль требуется на каждом сохранении, которое его несёт, — и на новом пользователе
 *       (создание учётной записи включает пароль), и на существующем;</li>
 *   <li>назначение ролей требуется тогда, когда набор ролей <b>меняется</b>: на новом
 *       пользователе — при непустом наборе, на существующем — при отличии от сохранённого.
 *       Обновление, которое набор не трогает, назначением ролей не является и второго права не
 *       требует; пустой набор в запросе читается как «ролей нет», то есть как снятие, а не как
 *       «поле не передавали»;</li>
 *   <li>чтобы отличить назначение от обновления, сервис читает сохранённое состояние тем же
 *       canonical read: цена честного решения — чтение пользователя (оно входит в ответ формы,
 *       см. {@code canAssignRoles}). Если строку не видно, решение о ней принимает сама граница
 *       записи — сервис не подменяет её отказ своим. Значения набора читаются отдельно от
 *       экземпляра ({@link PersistedUserState}): в живой сессии canonical read вернул бы тот же
 *       управляемый экземпляр, который вызывающий уже изменил, и «набор не менялся» было бы
 *       сравнением значения с самим собой. Сравнение идёт <b>до</b> вопросов о правах: решения
 *       читают гранты запросом, а запрос сбрасывает сессию;</li>
 *   <li><b>свой набор ролей не меняет никто</b>, включая держателя права: и выдача себе роли, и
 *       снятие с себя — одна и та же операция над своей учётной записью, поэтому отказ не зависит
 *       от выданных грантов. Роли своей учётной записи меняет другой держатель права.</li>
 * </ul>
 *
 * <p>Порядок обязателен: сначала авторизация, затем нормализация. Хэш — часть состояния, которое
 * валидируется ({@code password} объявлен {@code @NotBlank}), поэтому нормализация выполняется
 * <b>до</b> делегирования; но отказ не должен оставлять вызывающему изменённую сущность, поэтому
 * проверка идёт раньше записи.</p>
 *
 * <p>C4.6 волна E: класс больше не наследует compatibility base. Чтение и запись идут через
 * canonical handle ({@link EntityDataAccessResolver}): capability-граница, ранний RLS,
 * валидация, нумерация, lifecycle и события применяются тем же pipeline, что и у типов без
 * собственного сервиса.</p>
 */
@Measured
@Service
public class UserService implements BaseService<User, Long> {

    private final CanonicalEntityService<User> canonical;
    private final PasswordEncoder passwordEncoder;
    private final UserRoleAssignmentPermission roleAssignmentPermission;
    private final UserPasswordChangePermission passwordChangePermission;
    private final RlsCurrentUser currentUser;
    private final PersistedUserState persistedState;

    @Autowired
    public UserService(EntityDataAccessResolver dataAccessResolver, PasswordEncoder passwordEncoder,
                       UserRoleAssignmentPermission roleAssignmentPermission,
                       UserPasswordChangePermission passwordChangePermission,
                       RlsCurrentUser currentUser, PersistedUserState persistedState) {
        Objects.requireNonNull(dataAccessResolver, "dataAccessResolver must not be null");
        this.canonical = canonicalHandle(dataAccessResolver);
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder,
            "passwordEncoder must not be null");
        this.roleAssignmentPermission = Objects.requireNonNull(roleAssignmentPermission,
            "roleAssignmentPermission must not be null");
        this.passwordChangePermission = Objects.requireNonNull(passwordChangePermission,
            "passwordChangePermission must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
        this.persistedState = Objects.requireNonNull(persistedState,
            "persistedState must not be null");
    }

    /**
     * Сборка с явным canonical handle — только для тестов, где полный контекст не нужен.
     * Намеренно не {@code public}: Spring должен видеть ровно один кандидат на
     * autowiring, иначе он не выберет конструктор вообще.
     */
    UserService(CanonicalEntityService<User> canonical, PasswordEncoder passwordEncoder,
                UserRoleAssignmentPermission roleAssignmentPermission,
                UserPasswordChangePermission passwordChangePermission, RlsCurrentUser currentUser,
                PersistedUserState persistedState) {
        this.canonical = Objects.requireNonNull(canonical, "canonical must not be null");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder,
            "passwordEncoder must not be null");
        this.roleAssignmentPermission = Objects.requireNonNull(roleAssignmentPermission,
            "roleAssignmentPermission must not be null");
        this.passwordChangePermission = Objects.requireNonNull(passwordChangePermission,
            "passwordChangePermission must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
        this.persistedState = Objects.requireNonNull(persistedState,
            "persistedState must not be null");
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
        authorizeAdministration(entity);
        applyRawPasswordIfPresent(entity);
        return canonical.save(entity);
    }

    @Override
    public User create(User entity) {
        authorizeAdministration(entity);
        applyRawPasswordIfPresent(entity);
        return canonical.create(entity);
    }

    @Override
    public User update(User entity) {
        authorizeAdministration(entity);
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
     * Административная авторизация вызова: смена пароля и назначение ролей — отдельные операции
     * владельца, и свой набор ролей не меняет никто (см. класс).
     *
     * <p>Порядок обязателен: сначала сравнение с сохранённым состоянием (оба чтения не сбрасывают
     * сессию), затем требования операций — их решения читают гранты запросом, а запрос сбрасывает
     * сессию, поэтому спросить право раньше сравнения значило бы показать решению уже изменённое
     * вызывающим состояние.</p>
     */
    private void authorizeAdministration(User incoming) {
        String actor = currentUser.username();
        boolean passwordChange = rawPasswordCarried(incoming);
        Set<String> assigned = roleNames(incoming.getRoles());
        boolean roleChange = false;
        String persistedUsername = null;
        if (incoming.getId() == null) {
            // Новый пользователь: набор ролей назначается вместе с ним, сравнивать не с чем.
            roleChange = !assigned.isEmpty();
        } else {
            // Право чтения и видимость строки — у canonical read, как и у формы; значения набора
            // читаются отдельно, потому что экземпляр мог быть изменён вызывающим (см. PersistedUserState).
            Optional<User> row = canonical.findById(incoming.getId());
            if (row.isPresent()) {
                Optional<PersistedUserState.State> saved = persistedState.of(incoming.getId());
                if (saved.isPresent() && !assigned.equals(saved.get().roleNames())) {
                    roleChange = true;
                    persistedUsername = saved.get().username();
                }
            }
        }

        if (passwordChange) {
            passwordChangePermission.requireChangePassword(actor);
        }
        if (roleChange) {
            roleAssignmentPermission.requireAssignRoles(actor);
        }
        // Сохранённое имя, а не присланное: цель определяется строкой, к которой применяется
        // запись, иначе переименование в запросе выдавало бы свою строку за чужую.
        if (roleChange && persistedUsername != null && actor != null && !actor.isBlank()
                && actor.equals(persistedUsername)) {
            throw new AccessDeniedException(
                "Роли своей учётной записи не назначает никто: назначение ролей — действие "
                    + "над другой учётной записью");
        }
    }

    /** Несёт ли вызов новый пароль: на существующем пользователе пустое поле означает «не менять». */
    private static boolean rawPasswordCarried(User incoming) {
        String rawPassword = incoming.getRawPassword();
        return rawPassword != null && !rawPassword.isBlank();
    }

    /** Набор ролей как множество имён: сравнение идёт по тому, что назначается, а не по ссылкам. */
    private static Set<String> roleNames(Set<Role> roles) {
        if (roles == null) {
            return Set.of();
        }
        return roles.stream().map(Role::getName).collect(Collectors.toSet());
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
