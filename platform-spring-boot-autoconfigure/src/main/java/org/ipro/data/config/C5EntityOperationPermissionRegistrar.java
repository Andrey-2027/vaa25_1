package org.ipro.data.config;

import org.ipro.data.DataOperation;
import org.ipro.data.EntityDescriptor;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.rls.RlsDimensionKind;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.c5.C5Operation;
import org.ipro.rls.c5.C5OperationPermissionRegistry;
import org.ipro.rls.c5.C5PermissionDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Derives C5 entity operation definitions from canonical capabilities (ADR-0017 §5, plan C5.5).
 *
 * <p>The application never enumerates platform types by hand: a type gets {@code CREATE}/{@code
 * UPDATE}/{@code DELETE} definitions exactly when its descriptor allows that write intention, and a
 * type without a canonical handle (owned row, internal store, read-only type) gets none. The
 * definition key is the existing entity gate key, registered as a dynamic CHECK_ONLY dimension so
 * the grant admin can assign it — the same mechanism the read registrar already uses for read
 * keys.</p>
 *
 * <p>Registration grants nobody access; it only makes the key recognisable and assignable.</p>
 *
 * <p><b>Финальная граница записи — отдельный шаг, а не следствие выдачи ключа.</b>
 * Признак {@code rls.write-boundary.enforced} решает, требует ли flush зафиксированной
 * авторизации для типов с canonical write handle (ADR-0017 §9). Пока признак снят, шаг
 * регистрации печатает WARN со списком таких типов: снятое состояние названо вслух, а не
 * угадывается по поведению. Так же громко оно печатается и при включении, потому что цена
 * включения — миграция мест прямой записи, а не только сам флаг.</p>
 *
 * <p><b>Вооружение бывает пошаговым.</b> Необратимого «всё или ничего» здесь быть не должно:
 * миграция мест прямой записи идёт по типам, поэтому {@code rls.write-boundary.enforced-types}
 * называет конкретные типы, для которых flush уже требует авторизацию. Тип, названный в списке,
 * обязан иметь canonical write handle — иначе это ошибка конфигурации, а не тихий no-op;
 * неназванные типы остаются снятыми и печатаются в том же WARN. Список сужается до пустого,
 * когда миграция завершена, а признак «всё» становится единственным поведением.</p>
 */
final class C5EntityOperationPermissionRegistrar implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(
        C5EntityOperationPermissionRegistrar.class);

    private final EntityDescriptorCatalog catalog;
    private final RlsDimensionRegistry dimensionRegistry;
    private final C5OperationPermissionRegistry operationPermissionRegistry;
    private final boolean writeBoundaryEnforced;
    private final Set<String> writeBoundaryEnforcedTypes;

    C5EntityOperationPermissionRegistrar(EntityDescriptorCatalog catalog,
                                        RlsDimensionRegistry dimensionRegistry,
                                        C5OperationPermissionRegistry operationPermissionRegistry) {
        this(catalog, dimensionRegistry, operationPermissionRegistry, false, Set.of());
    }

    C5EntityOperationPermissionRegistrar(EntityDescriptorCatalog catalog,
                                        RlsDimensionRegistry dimensionRegistry,
                                        C5OperationPermissionRegistry operationPermissionRegistry,
                                        boolean writeBoundaryEnforced) {
        this(catalog, dimensionRegistry, operationPermissionRegistry, writeBoundaryEnforced,
            Set.of());
    }

    C5EntityOperationPermissionRegistrar(EntityDescriptorCatalog catalog,
                                        RlsDimensionRegistry dimensionRegistry,
                                        C5OperationPermissionRegistry operationPermissionRegistry,
                                        boolean writeBoundaryEnforced,
                                        Set<String> writeBoundaryEnforcedTypes) {
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.dimensionRegistry = Objects.requireNonNull(dimensionRegistry,
            "dimensionRegistry must not be null");
        this.operationPermissionRegistry = Objects.requireNonNull(operationPermissionRegistry,
            "operationPermissionRegistry must not be null");
        this.writeBoundaryEnforced = writeBoundaryEnforced;
        this.writeBoundaryEnforcedTypes = Set.copyOf(
            Objects.requireNonNull(writeBoundaryEnforcedTypes,
                "writeBoundaryEnforcedTypes must not be null"));
    }

    @Override
    public void afterPropertiesSet() {
        Set<String> stagedTypes = new TreeSet<>();
        Set<String> armedTypes = new TreeSet<>();
        Set<String> governedNames = new TreeSet<>();
        for (EntityDescriptor descriptor : catalog.all()) {
            Set<C5Operation> operations = operationCapabilities(descriptor);
            if (operations.isEmpty()) {
                continue;
            }
            governedNames.add(descriptor.type().getName());
            C5PermissionDefinition definition =
                C5PermissionDefinition.entityOperations(descriptor.type(), operations);
            dimensionRegistry.registerDynamicDimension(definition.key(), RlsDimensionKind.CHECK_ONLY);
            if (writeBoundaryEnforced || writeBoundaryEnforcedTypes.contains(
                    descriptor.type().getName())) {
                // Тот же факт, что даёт операционный ключ, называет тип предметом финальной
                // границы записи (ADR-0017 §9): тип с canonical write handle обязан предъявить
                // зафиксированную авторизацию на flush, даже когда у него нет измерений RLS.
                dimensionRegistry.registerWriteGovernedType(descriptor.type());
                armedTypes.add(descriptor.type().getName());
            } else {
                stagedTypes.add(descriptor.type().getName());
            }
            operationPermissionRegistry.register(definition);
        }
        requireNamedTypesHaveCanonicalWrite(governedNames);
        reportBoundaryState(armedTypes, stagedTypes);
    }

    /**
     * Имя типа в {@code enforced-types} без canonical write handle — ошибка конфигурации, а не
     * тихий no-op: тип либо вписан неверно, либо у него нет generic записи вовсе, и ждать от
     * него границы нечего. Сравнение по имени класса, а не по простому имени: список читается
     * из свойства человеком, но адресует конкретный тип в possibly одноимённом пакете.
     */
    private void requireNamedTypesHaveCanonicalWrite(Set<String> governedNames) {
        Set<String> unknown = new TreeSet<>(writeBoundaryEnforcedTypes);
        unknown.removeAll(governedNames);
        if (!unknown.isEmpty()) {
            throw new IllegalStateException("rls.write-boundary.enforced-types называет типы"
                + " без canonical write handle: " + unknown
                + "; граница записи существует только у типов с write-capability, а список —"
                + " признак завершённой миграции, а не способ разрешить запись");
        }
    }

    /**
     * Состояние границы печатается при старте всегда, включая частичное: вооружённые типы,
     * снятые типы и явно названные в списке. Молчаливого «флага нет — значит, всё в порядке»
     * не бывает: пока тип снят, прямой {@code EntityManager} остаётся входом в обход
     * canonical path.
     */
    private void reportBoundaryState(Set<String> armedTypes, Set<String> stagedTypes) {
        List<String> armed = new ArrayList<>(armedTypes);
        List<String> staged = new ArrayList<>(stagedTypes);
        if (armed.isEmpty()) {
            log.warn("Финальная граница записи снята (rls.write-boundary.enforced=false): {} типов"
                + " с canonical write handle не требуют авторизации на flush, поэтому прямой"
                + " EntityManager в обход canonical path не отклоняется: {}",
                staged.size(), staged);
            return;
        }
        log.warn("Финальная граница записи вооружена (ADR-0017 §9): flush требует"
            + " зафиксированную авторизацию для {} типов с canonical write handle: {};"
            + " снята для {} типов: {}", armed.size(), armed, staged.size(), staged);
    }

    private static Set<C5Operation> operationCapabilities(EntityDescriptor descriptor) {
        EnumSet<C5Operation> operations = EnumSet.noneOf(C5Operation.class);
        for (DataOperation operation : descriptor.capabilities().writes()) {
            switch (operation) {
                case CREATE -> operations.add(C5Operation.CREATE);
                case UPDATE -> operations.add(C5Operation.UPDATE);
                case DELETE -> operations.add(C5Operation.DELETE);
                default -> {
                    // Read intentions are not entity operations; they stay on the read path.
                }
            }
        }
        return operations;
    }
}
