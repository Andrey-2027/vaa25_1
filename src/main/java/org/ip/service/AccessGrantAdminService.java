package org.ip.service;

import org.ipro.rls.AccessGrant;
import org.ip.model.Role;
import org.ip.model.User;
import org.ipro.rls.AccessGrantRepository;
import org.ip.repository.RoleRepository;
import org.ip.repository.UserRepository;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsDimensionKind;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsDimensionValueCatalog;
import org.ipro.rls.c5.C5Operation;
import org.ipro.rls.c5.C5OperationPermissionRegistry;
import org.ipro.rls.c5.C5PermissionDecision;
import org.ipro.rls.c5.C5PermissionDecisionService;
import org.ipro.rls.c5.C5PermissionDefinition;
import org.ipro.rls.c5.C5ReadPermissionRegistry;
import org.ipro.telemetry.api.Measured;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Backend для админ-страницы настройки RLS-доступа (см. AdminView, вкладка "Доступ
 * (RLS)"): по выбранному измерению и субъекту (пользователь или роль) — либо матрица
 * "запись измерения → чтение/изменение/удаление" (FILTERABLE — JOURNAL, BRANCH, ...),
 * либо один переключатель без списка записей (CHECK_ONLY — "ENTITY:...", см.
 * RlsDimensionKind).
 *
 * Раньше был захардкожен на JOURNAL — обобщён через metadata-driven
 * {@link RlsDimensionValueCatalog} (для FILTERABLE) и через {@link RlsDimensionRegistry} (для перечисления CHECK_ONLY,
 * у которых списка записей нет по определению — там нечего перечислять, только флаги).
 */
@Service
@PreAuthorize("hasRole('ADMIN')")
@Measured
public class AccessGrantAdminService {

    @org.springframework.beans.factory.annotation.Autowired
    private org.ip.application.access.PermissionSubjectLock permissionSubjectLock;

    /**
     * Три флага прав; все false = записи гранта нет вообще (нет доступа). При сохранении
     * canRead нормализуется до read || update || delete ("изменение"/"удаление" без
     * "чтения" бессмысленны) — см. saveGrants/saveSingleGrant.
     */
    public record GrantFlags(boolean read, boolean update, boolean delete) {
        public static final GrantFlags NONE = new GrantFlags(false, false, false);
    }

    /** Одна строка матрицы для FILTERABLE-измерения — id/код/наименование конкретной записи. */
    public record ValueRow(Long id, String code, String name) {
    }

    /**
     * Определение, у которого нет ни одной решающей строки гранта ни у одного субъекта: ключ
     * зарегистрирован — и молча закрыт, включая для администратора (C5.8).
     *
     * <p>«Решающая» строка — та, которой определение действительно решает: операции — глобальная
     * строка с флагом объявленной операции (ADR-0017 §3), чтение — глобальная строка с флагом
     * чтения. Построчный {@code "*"} и value-scoped строки в счёт не идут: они не авторизуют ни
     * операцию, ни C5 read.</p>
     *
     * @param key        ключ определения ({@code ENTITY:...}, {@code ACTION:...}, {@code REST:...})
     * @param operations операции, которые определение решает
     */
    public record UngrantedDefinition(String key, Set<C5Operation> operations) {
    }

    private final AccessGrantRepository accessGrantRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RlsDimensionRegistry dimensionRegistry;
    private final AccessService accessService;
    private final RlsDimensionValueCatalog valueCatalog;
    private final C5ReadPermissionRegistry c5ReadPermissionRegistry;
    private final C5PermissionDecisionService c5PermissionDecisionService;
    private final C5OperationPermissionRegistry c5OperationPermissionRegistry;

    public AccessGrantAdminService(AccessGrantRepository accessGrantRepository,
                                   UserRepository userRepository,
                                   RoleRepository roleRepository,
                                   RlsDimensionRegistry dimensionRegistry,
                                   AccessService accessService,
                                   RlsDimensionValueCatalog valueCatalog,
                                   C5ReadPermissionRegistry c5ReadPermissionRegistry,
                                   C5PermissionDecisionService c5PermissionDecisionService,
                                   C5OperationPermissionRegistry c5OperationPermissionRegistry) {
        this.accessGrantRepository = accessGrantRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.dimensionRegistry = dimensionRegistry;
        this.accessService = accessService;
        this.valueCatalog = valueCatalog;
        this.c5ReadPermissionRegistry = c5ReadPermissionRegistry;
        this.c5PermissionDecisionService = c5PermissionDecisionService;
        this.c5OperationPermissionRegistry = c5OperationPermissionRegistry;
    }

    /**
     * Все измерения, которые можно настроить на этом экране: FILTERABLE с
     * зарегистрированным источником значений + ВСЕ CHECK_ONLY (у них список записей не
     * нужен — источник значений для них не регистрируется вообще, см. kindOf).
     */
    public List<String> availableDimensions() {
        var all = new TreeSet<>(dimensionRegistry.grantValueDimensions());
        for (String dimension : dimensionRegistry.dimensions()) {
            if (dimensionRegistry.kindOf(dimension) == RlsDimensionKind.CHECK_ONLY) {
                all.add(dimension);
            }
        }
        return List.copyOf(all);
    }

    public RlsDimensionKind kindOf(String dimension) {
        return dimensionRegistry.kindOf(dimension);
    }

    /**
     * Зарегистрированный C5 read key (entity/API, см. C5ReadPermissionRegistry).
     * Это представление READ общего реестра. ENTITY может также выражать write-флаги той же
     * global-строки; API выражает только чтение. Префикс, CHECK_ONLY и wildcard не заменяют регистрацию.
     */
    public boolean isC5ReadDimension(String dimension) {
        return c5ReadPermissionRegistry.isRegisteredReadKey(dimension);
    }

    /**
     * Измерение — операция (или операции) типа или именованная операция владельца: так его
     * объявило определение в реестре C5, а не префикс ключа и не CHECK_ONLY сам по себе.
     *
     * <p>Такие измерения выдаются ровно теми флагами, которые определение выражает: у
     * {@code ACTION:...} это единственная операция {@code EXECUTE}, у типа без UPDATE/DELETE —
     * только CREATE. Экран и сервис не предлагают флаг, которого определение не умеет решить:</n     * выданное право, которое ни на что не влияет (или влияет не тем, чем кажется),
     * администратор не может объяснить.</p>
     */
    public boolean isOperationDimension(String dimension) {
        return !operationsOf(dimension).isEmpty();
    }

    /** Операции, объявленные определением измерения; пусто — измерение не операционное. */
    private Set<C5Operation> operationsOf(String dimension) {
        return c5OperationPermissionRegistry.operationsOf(dimension);
    }

    /**
     * Именованная операция владельца ({@code ACTION:...}): её определение объявляет единственную
     * операцию {@code EXECUTE}, поэтому экран подписывает единственный флаг «выполнение», а не
     * «изменение». Отвечает только за подпись — выдача и показ одинаковы для всех операций.
     */
    public boolean isNamedOperationDimension(String dimension) {
        return operationsOf(dimension).contains(C5Operation.EXECUTE);
    }

    /**
     * Флаги, которые измерение вообще может выражать: для операционного измерения — объявленные
     * его определением, для C5 read — только чтение, для остальных CHECK_ONLY — прежние три.
     *
     * <p>P-U: полное ENTITY-определение объявляет READ и поддерживаемые операции записи.
     * Все независимые флаги адресуют общую строку. ACTION выражает только EXECUTE, REST — READ.</p>
     *
     * <p>Экран берёт набор отсюда, а не выводит его сам: пока набор флагов выводится в двух
     * местах (в экране и в выдаче), кнопка и записанная строка расходятся — именно так и
     * появилось требование «чтения» для операции, которое решает не она.</p>
     */
    public GrantFlags expressibleFlags(String dimension) {
        if (isOperationDimension(dimension)) {
            return operationGrantFlags(operationsOf(dimension));
        }
        if (isC5ReadDimension(dimension)) {
            return new GrantFlags(true, false, false);
        }
        return new GrantFlags(true, true, true);
    }

    /**
     * Флаги, которыми ключ операции решает объявленные операции (ADR-0017 §3): «изменение» —
     * CREATE/UPDATE/EXECUTE, «удаление» — DELETE, «чтение» — только если определение его объявило.
     * Одна формула и для показа на экране, и для выдачи: первичная выдача ({@code InitialGrantIssuance})
     * пишет ровно эти флаги, поэтому записанная строка не может говорить не то, что решает сервер.
     */
    public static GrantFlags operationGrantFlags(Set<C5Operation> operations) {
        return new GrantFlags(operations.contains(C5Operation.READ), allowsUpdate(operations),
            operations.contains(C5Operation.DELETE));
    }

    /** Флаг «изменение» решает CREATE/UPDATE и именованное EXECUTE (ADR-0017 §3). */
    private static boolean allowsUpdate(Set<C5Operation> operations) {
        return operations.contains(C5Operation.CREATE) || operations.contains(C5Operation.UPDATE)
            || operations.contains(C5Operation.EXECUTE);
    }

    public List<String> allUsernames() {
        return userRepository.findAll().stream().map(User::getUsername).sorted().toList();
    }

    public List<String> allRoleNames() {
        return roleRepository.findAll().stream().map(Role::getName).sorted().toList();
    }

    /** Все записи FILTERABLE-измерения, выведенные из metadata, без RLS текущего админа. */
    public List<ValueRow> allValues(String dimension) {
        if (dimensionRegistry.kindOf(dimension) != RlsDimensionKind.FILTERABLE) {
            throw new IllegalArgumentException("CHECK_ONLY dimension has no value catalog: " + dimension);
        }
        return valueCatalog.allIgnoringRls(dimension).stream()
            .map(v -> new ValueRow(v.id(), v.code(), v.name()))
            .toList();
    }

    /** Текущие гранты субъекта по записям FILTERABLE-измерения — Map&lt;id записи, GrantFlags&gt;. */
    public Map<Long, GrantFlags> currentGrantsByDimensionValue(String dimension, AccessGrant.SubjectType subjectType,
                                                                String subjectKey) {
        Map<Long, GrantFlags> result = new HashMap<>();
        for (AccessGrant grant : accessGrantRepository.findBySubjectTypeAndSubjectKeyAndDimension(
                subjectType, subjectKey, dimension)) {
            if (grant.getDimensionValueId() != null) {
                result.put(grant.getDimensionValueId(),
                    new GrantFlags(grant.isCanRead(), grant.isCanUpdate(), grant.isCanDelete()));
            }
        }
        return result;
    }

    /**
     * Применяет матрицу целиком для одного FILTERABLE-измерения: для каждой записи —
     * либо удаляет существующую строку (все три флага false), либо создаёт/обновляет её.
     */
    @Transactional
    public void saveGrants(String dimension, AccessGrant.SubjectType subjectType, String subjectKey,
                           Map<Long, GrantFlags> desiredByValueId) {
        permissionSubjectLock.acquire(subjectType, subjectKey);
        if (isOperationDimension(dimension) || isC5ReadDimension(dimension)) {
            throw new IllegalArgumentException("C5 permission '" + dimension
                + "' has no value-scoped grants; edit its independent flags with saveSingleGrant");
        }
        Map<Long, AccessGrant> existingByValueId = new HashMap<>();
        for (AccessGrant grant : accessGrantRepository.findBySubjectTypeAndSubjectKeyAndDimension(
                subjectType, subjectKey, dimension)) {
            if (grant.getDimensionValueId() != null) {
                existingByValueId.put(grant.getDimensionValueId(), grant);
            }
        }

        for (Map.Entry<Long, GrantFlags> entry : desiredByValueId.entrySet()) {
            Long valueId = entry.getKey();
            GrantFlags flags = entry.getValue();
            AccessGrant existing = existingByValueId.remove(valueId);
            boolean noAccess = !flags.read() && !flags.update() && !flags.delete();

            if (noAccess) {
                if (existing != null) {
                    accessGrantRepository.delete(existing);
                }
                continue;
            }

            AccessGrant grant = existing != null ? existing : new AccessGrant();
            grant.setSubjectType(subjectType);
            grant.setSubjectKey(subjectKey);
            grant.setDimension(dimension);
            grant.setDimensionValueId(valueId);
            grant.setCanRead(flags.read() || flags.update() || flags.delete());
            grant.setCanUpdate(flags.update());
            grant.setCanDelete(flags.delete());
            accessGrantRepository.save(grant);
        }
    }

    /**
     * Единственный грант субъекта по CHECK_ONLY-измерению — там нет "записей", есть
     * только сам факт доступа (dimensionValueId всегда null, см. RlsDimensionKind).
     */
    public GrantFlags currentSingleGrant(String dimension, AccessGrant.SubjectType subjectType, String subjectKey) {
        if (isOperationDimension(dimension)) {
            return currentOperationGrant(dimension, subjectType, subjectKey);
        }
        if (isC5ReadDimension(dimension)) {
            boolean read = accessGrantRepository
                .findBySubjectTypeAndSubjectKeyAndDimensionAndDimensionValueIdIsNull(
                    subjectType, subjectKey, dimension)
                .stream()
                .anyMatch(AccessGrant::isCanRead);
            // C5 read поддерживает только чтение: update/delete не являются рабочими C5-правами.
            return new GrantFlags(read, false, false);
        }
        return accessGrantRepository.findBySubjectTypeAndSubjectKeyAndDimension(subjectType, subjectKey, dimension)
            .stream()
            .findFirst()
            .map(g -> new GrantFlags(g.isCanRead(), g.isCanUpdate(), g.isCanDelete()))
            .orElse(GrantFlags.NONE);
    }

    /** Аналог saveGrants, но для CHECK_ONLY — без ключа-записи, ровно один грант на субъекта. */
    @Transactional
    public void saveSingleGrant(String dimension, AccessGrant.SubjectType subjectType, String subjectKey,
                                GrantFlags flags) {
        permissionSubjectLock.acquire(subjectType, subjectKey);
        if (dimension != null && dimension.startsWith("ATTR:")) {
            throw new IllegalArgumentException("Права на поля отменены; настраивайте доступ к сущности");
        }
        if (isOperationDimension(dimension)) {
            saveSingleOperationGrant(dimension, subjectType, subjectKey, flags);
            return;
        }
        if (isC5ReadDimension(dimension)) {
            saveSingleC5ReadGrant(dimension, subjectType, subjectKey, flags);
            return;
        }
        List<AccessGrant> existing = accessGrantRepository.findBySubjectTypeAndSubjectKeyAndDimension(
            subjectType, subjectKey, dimension);
        boolean noAccess = !flags.read() && !flags.update() && !flags.delete();

        if (noAccess) {
            existing.forEach(accessGrantRepository::delete);
            return;
        }

        AccessGrant grant = existing.stream().findFirst().orElseGet(AccessGrant::new);
        grant.setSubjectType(subjectType);
        grant.setSubjectKey(subjectKey);
        grant.setDimension(dimension);
        grant.setDimensionValueId(null);
        grant.setCanRead(flags.read() || flags.update() || flags.delete());
        grant.setCanUpdate(flags.update());
        grant.setCanDelete(flags.delete());
        accessGrantRepository.save(grant);
    }

    /**
     * Выдаётся ровно то, что объявляет определение, — у типа это READ/CREATE/UPDATE/DELETE
     * (флаг «изменение» решает CREATE/UPDATE, «удаление» — DELETE), у именованной операции
     * единственный флаг «изменение» решает {@code EXECUTE}. Выдача не записывает право, которого
     * определение не решает, и не нормализует «чтение» из остальных флагов. У ENTITY все флаги
     * самостоятельны и находятся в общей строке. Операция глобальна, поэтому value-scoped строки не переиспользуются
     * и не переписываются — как в C5 read.
     */
    private void saveSingleOperationGrant(String dimension, AccessGrant.SubjectType subjectType,
                                          String subjectKey, GrantFlags flags) {
        Set<C5Operation> operations = c5OperationPermissionRegistry.requireOperations(dimension);
        boolean readDeclared = operations.contains(C5Operation.READ);
        boolean updateDeclared = allowsUpdate(operations);
        boolean deleteDeclared = operations.contains(C5Operation.DELETE);
        requireDeclared(dimension, operations, "чтение", flags.read(), readDeclared);
        requireDeclared(dimension, operations, "изменение", flags.update(), updateDeclared);
        requireDeclared(dimension, operations, "удаление", flags.delete(), deleteDeclared);

        List<AccessGrant> global = accessGrantRepository
            .findBySubjectTypeAndSubjectKeyAndDimensionAndDimensionValueIdIsNull(
                subjectType, subjectKey, dimension);
        if (!flags.read() && !flags.update() && !flags.delete()) {
            global.forEach(accessGrantRepository::delete);
            return;
        }

        AccessGrant grant = global.stream().findFirst().orElseGet(AccessGrant::new);
        grant.setSubjectType(subjectType);
        grant.setSubjectKey(subjectKey);
        grant.setDimension(dimension);
        grant.setDimensionValueId(null);
        grant.setCanRead(readDeclared && flags.read());
        grant.setCanUpdate(updateDeclared && flags.update());
        grant.setCanDelete(deleteDeclared && flags.delete());
        accessGrantRepository.save(grant);
    }

    /**
     * Текущие флаги операционного измерения, сведённые к тому, что определение решает: строка с
     * флагом, которого определение не выражает (наследство прежней выдачи), показывается как
     * «права нет» — экран показывает решения определения, а не сырые колонки.
     */
    private GrantFlags currentOperationGrant(String dimension, AccessGrant.SubjectType subjectType,
                                             String subjectKey) {
        Set<C5Operation> operations = c5OperationPermissionRegistry.requireOperations(dimension);
        boolean readDeclared = operations.contains(C5Operation.READ);
        boolean updateDeclared = allowsUpdate(operations);
        boolean deleteDeclared = operations.contains(C5Operation.DELETE);
        return accessGrantRepository
            .findBySubjectTypeAndSubjectKeyAndDimensionAndDimensionValueIdIsNull(
                subjectType, subjectKey, dimension)
            .stream()
            .findFirst()
            .map(grant -> new GrantFlags(readDeclared && grant.isCanRead(),
                updateDeclared && grant.isCanUpdate(), deleteDeclared && grant.isCanDelete()))
            .orElse(GrantFlags.NONE);
    }

    /**
     * Отказ на флаг, которого определение не выражает: иначе администратор выдал бы право,
     * которое ни на что не влияет, и считал бы, что открыл операцию. Сообщение называет и ключ
     * определения, и его операции — вместо того чтобы молча записать не то.
     */
    private static void requireDeclared(String dimension, Set<C5Operation> operations, String flag,
                                        boolean requested, boolean declared) {
        if (requested && !declared) {
            throw new IllegalArgumentException("C5 definition '" + dimension + "' declares "
                + operations + " and cannot express '" + flag
                + "': issue a flag the definition decides");
        }
    }

    /**
     * C5 read: редактируется только exact global строка (dimensionValueId == null) и только
     * поддерживаемый флаг чтения; update/delete на серверной границе отвергаются, value-scoped
     * исторические строки не меняются и не удаляются (F-REST-READ-3, З2).
     */
    private void saveSingleC5ReadGrant(String dimension, AccessGrant.SubjectType subjectType,
                                       String subjectKey, GrantFlags flags) {
        if (flags.update() || flags.delete()) {
            throw new IllegalArgumentException("C5 read permission '" + dimension
                + "' supports only the read flag; update/delete are not C5 read rights");
        }
        List<AccessGrant> global = accessGrantRepository
            .findBySubjectTypeAndSubjectKeyAndDimensionAndDimensionValueIdIsNull(
                subjectType, subjectKey, dimension);
        if (!flags.read()) {
            global.forEach(accessGrantRepository::delete);
            return;
        }
        AccessGrant grant = global.stream().findFirst().orElseGet(AccessGrant::new);
        grant.setSubjectType(subjectType);
        grant.setSubjectKey(subjectKey);
        grant.setDimension(dimension);
        grant.setDimensionValueId(null);
        grant.setCanRead(true);
        grant.setCanUpdate(false);
        grant.setCanDelete(false);
        accessGrantRepository.save(grant);
    }

    /**
     * Отчёт «определение зарегистрировано, грантов нет» (C5.8): что объявлено определениями —
     * операции типов, именованные операции, read-ключи и API — и при этом не выдано никому.
     *
     * <p>Пока такого отчёта не было, закрытое определение было видно только тому, кто сам
     * догадается открыть его ключ: новый тип, объявленный платформой, оказывался молча закрыт
     * <b>включая для администратора</b> — ни одна строка гранта его не открывает, а спросить
     * «кому выдано» было негде. Отчёт отвечает ровно на этот вопрос и намеренно считается по
     * <b>всем субъектам</b>, а не по выбранному: выбранный может не иметь права, то есть это не
     * признак закрытости вообще.</p>
     *
     * <p>Решающая строка — не любая: wildcard {@code "*"} и value-scoped строки операции не
     * авторизуют (ADR-0017 §3), поэтому наличие таких строк не скрывает определение из отчёта.
     * Отчёт ничего не выдает и не меняет: он перечисляет определения, у которых решающих строк
     * нет ни у одного субъекта, — решение о выдаче остаётся за администратором.</p>
     */
    public List<UngrantedDefinition> ungrantedDefinitions() {
        Map<String, List<AccessGrant>> globalByDimension = accessGrantRepository.findAll().stream()
            .filter(grant -> grant.getDimensionValueId() == null)
            .collect(Collectors.groupingBy(AccessGrant::getDimension));
        List<UngrantedDefinition> missing = new ArrayList<>();
        for (C5PermissionDefinition definition : c5OperationPermissionRegistry.definitions()) {
            if (!decidesAnyOperation(globalByDimension.getOrDefault(definition.key(), List.of()),
                    definition.operations())) {
                missing.add(new UngrantedDefinition(definition.key(), definition.operations()));
            }
        }
        for (String key : c5ReadPermissionRegistry.registeredKeys()) {
            if (c5OperationPermissionRegistry.isRegistered(key)) continue;
            boolean granted = globalByDimension.getOrDefault(key, List.of()).stream()
                .anyMatch(AccessGrant::isCanRead);
            if (!granted) {
                missing.add(new UngrantedDefinition(key, Set.of(C5Operation.READ)));
            }
        }
        missing.sort(Comparator.comparing(UngrantedDefinition::key));
        return List.copyOf(missing);
    }

    /** Решает ли хоть одна глобальная строка объявленные операции определения. */
    private static boolean decidesAnyOperation(List<AccessGrant> rows, Set<C5Operation> operations) {
        GrantFlags deciding = operationGrantFlags(operations);
        return rows.stream().anyMatch(row -> (deciding.read() && row.isCanRead())
            || (deciding.update() && row.isCanUpdate())
            || (deciding.delete() && row.isCanDelete()));
    }

    /**
     * Эффективные права субъекта по ВСЕМ настраиваемым измерениям (см.
     * {@link #availableDimensions()}) — свёртка прямых грантов и ролей (для
     * пользователя) или собственных грантов (для роли), с учётом wildcard "*".
     * Источник данных для диалога «Эффективные права» в AdminView.
     */
    public Map<String, AccessService.EffectiveGrant> collectEffective(AccessGrant.SubjectType subjectType,
                                                                      String subjectKey) {
        Map<String, AccessService.EffectiveGrant> result = new LinkedHashMap<>();
        for (String dimension : availableDimensions()) {
            if (isOperationDimension(dimension)) {
                result.put(dimension, operationEffectiveGrant(dimension, subjectType, subjectKey));
                continue;
            }
            if (isC5ReadDimension(dimension)) {
                result.put(dimension, c5EffectiveGrant(dimension, subjectType, subjectKey));
                continue;
            }
            AccessService.EffectiveGrant grant = subjectType == AccessGrant.SubjectType.USER
                ? accessService.effectiveGrants(dimension, subjectKey)
                : accessService.effectiveGrantsOfRole(dimension, subjectKey);
            result.put(dimension, grant);
        }
        return result;
    }

    /**
     * Эффективные права по операционному измерению — из решения C5, а не из RLS-свёртки.
     *
     * <p>Свёртка {@link AccessService#effectiveGrants} читает гранты с подстановкой wildcard
     * {@code "*"} и учитывает value-scoped строки; решение операции — нет (ADR-0017 §3). Пока
     * диалог брал столбцы «Изменение»/«Удаление» из свёртки, он показывал право, которое сервер
     * отказывает: держателю одной wildcard-строки экран говорил «Да», а canonical write и
     * именованная операция отказывали. Теперь спрошено ровно то, что спросит enforcement, тем же
     * движком — теми же определениями, тем же ключом и тем же отображением флагов
     * (CREATE/UPDATE/EXECUTE → «изменение», DELETE → «удаление»).</p>
     *
     * <p>P-U: чтение и запись спрашивают самостоятельные операции единого ENTITY-определения.
     * ACTION/REST также спрашивают только объявленные операции; RLS-wildcard не участвует.
     */
    private AccessService.EffectiveGrant operationEffectiveGrant(String dimension,
                                                                 AccessGrant.SubjectType subjectType,
                                                                 String subjectKey) {
        Set<C5Operation> operations = operationsOf(dimension);
        Set<String> sources = new LinkedHashSet<>();
        boolean read = false;
        boolean update = false;
        boolean delete = false;
        for (C5Operation operation : operations) {
            C5PermissionDecision decision = c5PermissionDecisionService
                .decideForSubject(subjectType, subjectKey, dimension, operation);
            if (!decision.allowed()) {
                continue;
            }
            switch (operation) {
                case READ -> read = true;
                case DELETE -> delete = true;
                default -> update = true; // CREATE/UPDATE/EXECUTE решаются флагом «изменение»
            }
            sources.addAll(decision.sources());
        }
        return new AccessService.EffectiveGrant(read, update, delete, false, List.of(),
            List.copyOf(sources));
    }

    /**
     * C5 read решение для админ-экрана строится общей C5-границей, а не AccessService:
     * wildcard "*" и value-scoped строки не являются глобальным C5 read, write/delete не
     * являются рабочими C5-правами. Источники сохраняют формат EffectiveGrant
     * ("прямой", "роль: X") — поэтому показанное решение совпадает с серверным.
     */
    private AccessService.EffectiveGrant c5EffectiveGrant(String dimension,
                                                          AccessGrant.SubjectType subjectType,
                                                          String subjectKey) {
        C5PermissionDecision decision = c5PermissionDecisionService
            .decideForSubject(subjectType, subjectKey, dimension, C5Operation.READ);
        return new AccessService.EffectiveGrant(decision.allowed(), false, false, false,
            List.of(), decision.sources());
    }
}
