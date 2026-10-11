package org.ip.service;

import org.ip.model.Nomenclature;
import org.ip.model.Role;
import org.ip.repository.RoleRepository;
import org.ip.repository.UserRepository;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsDimensionValueCatalog;
import org.ipro.rls.c5.C5Operation;
import org.ipro.rls.c5.C5OperationPermissionRegistry;
import org.ipro.rls.c5.C5PermissionDecisionService;
import org.ipro.rls.c5.C5PermissionDefinition;
import org.ipro.rls.c5.C5PermissionKeys;
import org.ipro.rls.c5.C5ReadPermissionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C5.8: отчёт «определение зарегистрировано, грантов нет» — что он считает грантом, а что нет.
 *
 * <p>Ценность отчёта — в ответе на вопрос «кто-нибудь вообще может это решить», поэтому его
 * нельзя строить по «есть ли строки у выбранного субъекта» или по «есть ли строка вообще».
 * Здесь закреплено ровно различение решающей строки: wildcard {@code "*"}, value-scoped строка и
 * флаг, которого определение не выражает, определению не помогают (ADR-0017 §3); C5 read решает
 * только флаг чтения глобальной строки.</p>
 */
class UngrantedDefinitionsReportTest {

    private static final String NOMENCLATURE_OPERATIONS = C5PermissionKeys.entityOperations(Nomenclature.class);
    private static final String NOMENCLATURE_READ = C5PermissionKeys.entityRead(Nomenclature.class);
    private static final String ROLE_OPERATIONS = C5PermissionKeys.entityOperations(Role.class);

    private final AccessGrantRepository grants = mock(AccessGrantRepository.class);
    private final C5OperationPermissionRegistry operationDefinitions = new C5OperationPermissionRegistry();
    private final C5ReadPermissionRegistry readDefinitions = new C5ReadPermissionRegistry();
    private final List<AccessGrant> rows = new ArrayList<>();

    private AccessGrantAdminService service;

    @BeforeEach
    void setUp() {
        operationDefinitions.register(C5PermissionDefinition.entityOperations(Nomenclature.class,
            Set.of(C5Operation.CREATE, C5Operation.UPDATE, C5Operation.DELETE)));
        // Тип, который объявляет только удаление: строка с «изменением» его не решает.
        operationDefinitions.register(C5PermissionDefinition.entityOperations(Role.class,
            Set.of(C5Operation.DELETE)));
        readDefinitions.registerReadKey(NOMENCLATURE_READ);
        when(grants.findAll()).thenReturn(rows);
        service = new AccessGrantAdminService(grants, mock(UserRepository.class),
            mock(RoleRepository.class), mock(RlsDimensionRegistry.class), mock(AccessService.class),
            mock(RlsDimensionValueCatalog.class), readDefinitions,
            mock(C5PermissionDecisionService.class), operationDefinitions);
    }

    @Test
    void everyRegisteredDefinitionWithoutADecidingRowIsReported() {
        assertThat(keys()).containsExactlyInAnyOrder(NOMENCLATURE_OPERATIONS, NOMENCLATURE_READ, ROLE_OPERATIONS);
        assertThat(service.ungrantedDefinitions())
            .filteredOn(definition -> definition.key().equals(NOMENCLATURE_READ))
            .singleElement()
            .satisfies(definition -> assertThat(definition.operations())
                .isEqualTo(Set.of(C5Operation.READ)));
    }

    @Test
    void rowsThatDoNotDecideTheDefinitionDoNotHideIt() {
        rows.add(grant("*", null, true, true, true));
        rows.add(grant(NOMENCLATURE_OPERATIONS, 42L, true, true, true));
        // Наследство прежней выдачи: глобальная строка, но с флагом, которого определение не
        // выражает. Операцию она не решает и из отчёта определение не убирает.
        rows.add(grant(NOMENCLATURE_OPERATIONS, null, true, false, false));
        rows.add(grant(ROLE_OPERATIONS, null, false, true, false));
        rows.add(grant(NOMENCLATURE_READ, null, false, true, false));

        assertThat(keys()).containsExactlyInAnyOrder(NOMENCLATURE_OPERATIONS, NOMENCLATURE_READ, ROLE_OPERATIONS);
    }

    @Test
    void aDecidingRowAnywhereClearsTheDefinition() {
        rows.add(grant(NOMENCLATURE_OPERATIONS, null, false, true, false));
        assertThat(keys())
            .as("CREATE/UPDATE решаются флагом «изменение» независимо от субъекта")
            .doesNotContain(NOMENCLATURE_OPERATIONS)
            .contains(ROLE_OPERATIONS, NOMENCLATURE_READ);

        rows.add(grant(ROLE_OPERATIONS, null, false, false, true));
        assertThat(keys())
            .as("определение только с DELETE решает флаг «удаление»")
            .doesNotContain(ROLE_OPERATIONS)
            .contains(NOMENCLATURE_READ);

        rows.add(grant(NOMENCLATURE_READ, null, true, false, false));
        assertThat(keys())
            .as("read-ключ решает флаг чтения глобальной строки")
            .doesNotContain(NOMENCLATURE_READ);

        assertThat(keys()).as("все определения получили решающую строку").isEmpty();
    }

    private List<String> keys() {
        return service.ungrantedDefinitions().stream()
            .map(AccessGrantAdminService.UngrantedDefinition::key)
            .toList();
    }

    private static AccessGrant grant(String dimension, Long valueId,
                                     boolean read, boolean update, boolean delete) {
        AccessGrant grant = new AccessGrant();
        grant.setSubjectType(AccessGrant.SubjectType.USER);
        grant.setSubjectKey("report-subject");
        grant.setDimension(dimension);
        grant.setDimensionValueId(valueId);
        grant.setCanRead(read);
        grant.setCanUpdate(update);
        grant.setCanDelete(delete);
        return grant;
    }
}
