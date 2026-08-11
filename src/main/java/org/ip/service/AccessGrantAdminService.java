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
import org.ipro.rls.RlsDimensionValueSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Backend для админ-страницы настройки RLS-доступа (см. AdminView, вкладка "Доступ
 * (RLS)"): по выбранному измерению и субъекту (пользователь или роль) — либо матрица
 * "запись измерения → чтение/изменение/удаление" (FILTERABLE — JOURNAL, BRANCH, ...),
 * либо один переключатель без списка записей (CHECK_ONLY — "ENTITY:...", см.
 * RlsDimensionKind).
 *
 * Раньше был захардкожен на JOURNAL — обобщён через {@link RlsDimensionValueSource}
 * (для FILTERABLE) и через {@link RlsDimensionRegistry} (для перечисления CHECK_ONLY,
 * у которых списка записей нет по определению — там нечего перечислять, только флаги).
 */
@Service
public class AccessGrantAdminService {

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

    private final AccessGrantRepository accessGrantRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RlsDimensionRegistry dimensionRegistry;
    private final AccessService accessService;
    private final Map<String, RlsDimensionValueSource<Object>> sourcesByDimension;

    @SuppressWarnings({"unchecked", "rawtypes"})
    public AccessGrantAdminService(AccessGrantRepository accessGrantRepository,
                                   UserRepository userRepository,
                                   RoleRepository roleRepository,
                                   RlsDimensionRegistry dimensionRegistry,
                                   AccessService accessService,
                                   List<RlsDimensionValueSource> sources) {
        this.accessGrantRepository = accessGrantRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.dimensionRegistry = dimensionRegistry;
        this.accessService = accessService;
        Map<String, RlsDimensionValueSource<Object>> byDimension = new HashMap<>();
        for (RlsDimensionValueSource source : sources) {
            byDimension.put(source.dimension(), source);
        }
        this.sourcesByDimension = byDimension;
    }

    /**
     * Все измерения, которые можно настроить на этом экране: FILTERABLE с
     * зарегистрированным источником значений + ВСЕ CHECK_ONLY (у них список записей не
     * нужен — источник значений для них не регистрируется вообще, см. kindOf).
     */
    public List<String> availableDimensions() {
        var all = new TreeSet<>(sourcesByDimension.keySet());
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

    public List<String> allUsernames() {
        return userRepository.findAll().stream().map(User::getUsername).sorted().toList();
    }

    public List<String> allRoleNames() {
        return roleRepository.findAll().stream().map(Role::getName).sorted().toList();
    }

    /** Все записи FILTERABLE-измерения (без RLS-ограничений текущего админа) — см. RlsDimensionValueSource. */
    public List<ValueRow> allValues(String dimension) {
        RlsDimensionValueSource<Object> source = sourceFor(dimension);
        return source.allIgnoringRls().stream()
            .map(v -> new ValueRow(source.idOf(v), source.displayCode(v), source.displayName(v)))
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

    private RlsDimensionValueSource<Object> sourceFor(String dimension) {
        RlsDimensionValueSource<Object> source = sourcesByDimension.get(dimension);
        if (source == null) {
            throw new IllegalArgumentException("Нет источника значений для измерения RLS: " + dimension);
        }
        return source;
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
            AccessService.EffectiveGrant grant = subjectType == AccessGrant.SubjectType.USER
                ? accessService.effectiveGrants(dimension, subjectKey)
                : accessService.effectiveGrantsOfRole(dimension, subjectKey);
            result.put(dimension, grant);
        }
        return result;
    }
}