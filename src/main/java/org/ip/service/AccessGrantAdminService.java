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
import org.ipro.rls.c5.C5ReadDecisionService;
import org.ipro.rls.c5.C5ReadPermissionRegistry;
import org.ipro.telemetry.api.Measured;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.ArrayList;
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
 * Раньше был захардкожен на JOURNAL — обобщён через metadata-driven
 * {@link RlsDimensionValueCatalog} (для FILTERABLE) и через {@link RlsDimensionRegistry} (для перечисления CHECK_ONLY,
 * у которых списка записей нет по определению — там нечего перечислять, только флаги).
 */
@Service
@PreAuthorize("hasRole('ADMIN')")
@Measured
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
    private final RlsDimensionValueCatalog valueCatalog;
    private final C5ReadPermissionRegistry c5ReadPermissionRegistry;
    private final C5ReadDecisionService c5ReadDecisionService;

    public AccessGrantAdminService(AccessGrantRepository accessGrantRepository,
                                   UserRepository userRepository,
                                   RoleRepository roleRepository,
                                   RlsDimensionRegistry dimensionRegistry,
                                   AccessService accessService,
                                   RlsDimensionValueCatalog valueCatalog,
                                   C5ReadPermissionRegistry c5ReadPermissionRegistry,
                                   C5ReadDecisionService c5ReadDecisionService) {
        this.accessGrantRepository = accessGrantRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.dimensionRegistry = dimensionRegistry;
        this.accessService = accessService;
        this.valueCatalog = valueCatalog;
        this.c5ReadPermissionRegistry = c5ReadPermissionRegistry;
        this.c5ReadDecisionService = c5ReadDecisionService;
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
     * Зарегистрированный C5 read key (entity/API/attribute, см. C5ReadPermissionRegistry).
     * Такие измерения редактируются только флагом чтения и без dimension value; распознаются
     * по явной регистрации, а не по префиксу, CHECK_ONLY или wildcard (F-REST-READ-3, З2).
     */
    public boolean isC5ReadDimension(String dimension) {
        return c5ReadPermissionRegistry.isRegisteredReadKey(dimension);
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
        if (isC5ReadDimension(dimension)) {
            throw new IllegalArgumentException("C5 read permission '" + dimension
                + "' has no value-scoped grants; edit its read flag with saveSingleGrant");
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
     * Эффективные права субъекта по ВСЕМ настраиваемым измерениям (см.
     * {@link #availableDimensions()}) — свёртка прямых грантов и ролей (для
     * пользователя) или собственных грантов (для роли), с учётом wildcard "*".
     * Источник данных для диалога «Эффективные права» в AdminView.
     */
    public Map<String, AccessService.EffectiveGrant> collectEffective(AccessGrant.SubjectType subjectType,
                                                                      String subjectKey) {
        Map<String, AccessService.EffectiveGrant> result = new LinkedHashMap<>();
        for (String dimension : availableDimensions()) {
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
     * C5 read решение для админ-экрана строится общей C5 read-границей, а не AccessService:
     * wildcard "*" и value-scoped строки не являются глобальным C5 read, write/delete не
     * являются рабочими C5-правами. Источники сохраняют формат EffectiveGrant
     * ("прямой", "роль: X") — поэтому показанное решение совпадает с серверным.
     */
    private AccessService.EffectiveGrant c5EffectiveGrant(String dimension,
                                                          AccessGrant.SubjectType subjectType,
                                                          String subjectKey) {
        C5ReadDecisionService.C5ReadDecision decision =
            c5ReadDecisionService.decide(dimension, subjectType, subjectKey);
        List<String> sources = new ArrayList<>();
        if (subjectType == AccessGrant.SubjectType.USER) {
            if (decision.direct()) {
                sources.add("прямой");
            }
            decision.roles().forEach(role -> sources.add("роль: " + role));
        } else if (decision.granted()) {
            sources.add("роль: " + subjectKey);
        }
        return new AccessService.EffectiveGrant(decision.granted(), false, false, false,
            List.of(), List.copyOf(sources));
    }
}
