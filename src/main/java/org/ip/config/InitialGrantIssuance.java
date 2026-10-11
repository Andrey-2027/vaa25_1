package org.ip.config;

import org.ip.application.user.UserPasswordChangePermission;
import org.ip.application.user.UserRoleAssignmentPermission;
import org.ip.service.AccessGrantAdminService;
import org.ip.service.AccessGrantAdminService.GrantFlags;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.c5.C5Operation;
import org.ipro.rls.c5.C5OperationPermissionRegistry;
import org.ipro.rls.c5.C5PermissionDefinition;
import org.ipro.rls.c5.C5PermissionKeys;
import org.ipro.rls.c5.C5ReadPermissionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Первичная выдача прав (C5.8): идемпотентно материализует объявленные определения в строки
 * грантов для выбранных ролей — иначе после включения C5-операций запись откажет и администратору
 * (предсказание C5.4, подтверждённое C5.7b).
 *
 * <p><b>Что выдаётся.</b> Плоскость сущностей: read-ключи {@code ENTITY:<FQN>} (флаг чтения) и
 * определения операций {@code ENTITY:<Имя>} (объявленные операции решаются флагами
 * «изменение»/«удаление», ADR-0017 §3). Источник — те же реестры определений, что спрашивает
 * решение и показывает админ-экран: выдача не может разойтись с тем, что решит сервер.</p>
 *
 * <p><b>Административный минимум.</b> Сверх плоскости сущностей выдаются названные в
 * {@code app.security-baseline.operations} именованные операции; по умолчанию — назначение ролей
 * и смена пароля ({@link UserRoleAssignmentPermission}, {@link UserPasswordChangePermission}). Без
 * них свежий контур закрыт и для администратора: это отдельные определения (C5.8), и их спрашивает
 * и серверный путь, и граница записи. Остальные {@code ACTION:...} и {@code REST:...} остаются
 * точечной выдачей администратора: в минимум попадает только то, без чего роль не исполняет свою
 * работу. Названный, но незарегистрированный ключ — отказ при старте, а не тихая выдача.</p>
 *
 * <p><b>Идемпотентность и неприкосновенность данных.</b> Существующая строка субъекта по ключу не
 * переписывается и не дублируется: выдача добавляет только отсутствующие строки. Если строка есть
 * с флагами, которых определение не выражает (наследство прежней выдачи), она остаётся как есть,
 * а её состояние показывают экран и отчёт — выдача не исправляет чужие строки молча.</p>
 *
 * <p><b>Включение — явное.</b> По умолчанию выдача не выполняется
 * ({@code app.security-baseline.enabled=false}): право не выдаётся никому при старте, его выдаёт
 * администратор — или оператор, осознанно включающий первичную выдачу вместе с включением
 * enforcement. {@code prod} исключён профилем: в проде первичная выдача — решение оператора
 * развёртывания, а не свойство локального контура.</p>
 *
 * <p>Компонент носит имя {@code InitialGrantIssuance}, а пользовательский срез называет его
 * «первичная выдача»: ключ {@code app.security-baseline.*} читается как «базовые права», а он
 * исторически занят RLS-флагами; имя свойства оставлено простым для оператора.</p>
 */
@Component
@Profile("!prod")
@ConditionalOnProperty(name = InitialGrantIssuance.ENABLED_PROPERTY, havingValue = "true")
public class InitialGrantIssuance implements CommandLineRunner {

    /** Свойство включения первичной выдачи; по умолчанию {@code false} — право выдаёт администратор. */
    public static final String ENABLED_PROPERTY = "app.security-baseline.enabled";

    /**
     * Именованные операции административного минимума: выдаются тем же ролям, что и плоскость
     * сущностей. Значение по умолчанию — в плейсхолдере свойства; незарегистрированный ключ здесь
     * останавливает старт (fail-closed), потому что выдача не может говорить не то, что решит
     * сервер.
     */
    public static final String OPERATIONS_PROPERTY = "app.security-baseline.operations";

    private static final Logger log = LoggerFactory.getLogger(InitialGrantIssuance.class);

    private final AccessGrantRepository grants;
    private final C5OperationPermissionRegistry operationDefinitions;
    private final C5ReadPermissionRegistry readDefinitions;
    private final List<String> roles;
    private final List<String> operations;

    public InitialGrantIssuance(AccessGrantRepository grants,
                                C5OperationPermissionRegistry operationDefinitions,
                                C5ReadPermissionRegistry readDefinitions,
                                @Value("${app.security-baseline.roles:ADMIN}") List<String> roles,
                                @Value("${app.security-baseline.operations:USER:ASSIGN_ROLES,USER:CHANGE_PASSWORD}")
                                List<String> operations) {
        this.grants = Objects.requireNonNull(grants, "grants must not be null");
        this.operationDefinitions = Objects.requireNonNull(operationDefinitions,
            "operationDefinitions must not be null");
        this.readDefinitions = Objects.requireNonNull(readDefinitions,
            "readDefinitions must not be null");
        this.roles = List.copyOf(Objects.requireNonNull(roles, "roles must not be null"));
        this.operations = List.copyOf(Objects.requireNonNull(operations,
            "operations must not be null"));
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (roles.isEmpty()) {
            log.warn("Первичная выдача включена ({}), но роли не названы ({} пуст): права никому"
                + " не выданы", ENABLED_PROPERTY, "app.security-baseline.roles");
            return;
        }
        for (String role : roles) {
            int issued = issueForRole(role);
            log.info("Первичная выдача ({}): роли '{}' добавлено {} строк — read-ключи, операции"
                + " сущностей и административный минимум именованных операций; существующие строки"
                + " не переписывались", ENABLED_PROPERTY, role, issued);
        }
    }

    /**
     * Выдаёт роли недостающие строки: плоскость сущностей и административный минимум именованных
     * операций; возвращает число добавленных строк.
     *
     * <p>Метод — та же операция, что делает {@link #run}, и вызывается в тестах напрямую: форма
     * выдачи проверяется на том же коде, что идёт при старте, а не на его копии.</p>
     */
    @Transactional
    public int issueForRole(String roleName) {
        Objects.requireNonNull(roleName, "roleName must not be null");
        if (roleName.isBlank()) {
            throw new IllegalArgumentException("app.security-baseline.roles must not contain blank names");
        }
        int issued = 0;
        for (C5PermissionDefinition definition : operationDefinitions.definitions()) {
            if (!isEntityKey(definition.key()) || hasGlobalRow(roleName, definition.key())) {
                continue;
            }
            grants.save(globalGrant(roleName, definition.key(),
                AccessGrantAdminService.operationGrantFlags(definition.operations())));
            issued++;
        }
        for (String key : readDefinitions.registeredKeys()) {
            if (!isEntityKey(key) || hasGlobalRow(roleName, key)) {
                continue;
            }
            grants.save(globalGrant(roleName, key, new GrantFlags(true, false, false)));
            issued++;
        }
        for (String permissionId : operations) {
            issued += issueOperation(roleName, permissionId);
        }
        grants.flush();
        return issued;
    }

    /**
     * Выдаёт одну именованную операцию административного минимума; возвращает 1, если строка
     * добавлена, и 0, если она уже есть.
     *
     * <p>Ключ обязан быть зарегистрированным определением ровно с операцией {@code EXECUTE}:
     * выдать ключ, которого решение не знает, — это выдать обещание, которого сервер не исполнит
     * (ADR-0017 §5). Отсюда же отклоняется имя измерения вместо имени операции: у него нет EXECUTE.</p>
     */
    private int issueOperation(String roleName, String permissionId) {
        if (permissionId == null || permissionId.isBlank()) {
            throw new IllegalArgumentException(
                OPERATIONS_PROPERTY + " must not contain blank names");
        }
        String key = C5PermissionKeys.action(permissionId);
        if (!operationDefinitions.isRegistered(key)) {
            throw new IllegalStateException(OPERATIONS_PROPERTY + " называет '" + permissionId
                + "', но определение '" + key + "' не зарегистрировано: выдача не может"
                + " материализовать то, чего решение не знает");
        }
        Set<C5Operation> declared = operationDefinitions.requireOperations(key);
        if (!declared.contains(C5Operation.EXECUTE)) {
            throw new IllegalStateException(OPERATIONS_PROPERTY + " называет '" + permissionId
                + "', но ключ '" + key + "' не объявляет EXECUTE (объявлено " + declared
                + "): административный минимум — это именованные операции, а не измерения типов");
        }
        if (hasGlobalRow(roleName, key)) {
            return 0;
        }
        grants.save(globalGrant(roleName, key,
            AccessGrantAdminService.operationGrantFlags(declared)));
        return 1;
    }

    /** Строка уже есть у субъекта по этому ключу — не дублируем и не переписываем. */
    private boolean hasGlobalRow(String roleName, String dimension) {
        return !grants.findBySubjectTypeAndSubjectKeyAndDimensionAndDimensionValueIdIsNull(
            AccessGrant.SubjectType.ROLE, roleName, dimension).isEmpty();
    }

    private static boolean isEntityKey(String key) {
        return key.startsWith("ENTITY:");
    }

    private static AccessGrant globalGrant(String roleName, String dimension, GrantFlags flags) {
        AccessGrant grant = new AccessGrant();
        grant.setSubjectType(AccessGrant.SubjectType.ROLE);
        grant.setSubjectKey(roleName);
        grant.setDimension(dimension);
        grant.setDimensionValueId(null);
        grant.setCanRead(flags.read());
        grant.setCanUpdate(flags.update());
        grant.setCanDelete(flags.delete());
        return grant;
    }
}
