package org.ip.config;

import org.ip.application.user.UserPasswordChangePermission;
import org.ip.application.user.UserRoleAssignmentPermission;
import org.ip.model.Nomenclature;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.c5.C5PermissionKeys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проводка первичной выдачи: с включённым свойством bean существует и срабатывает при старте
 * ровно для названных ролей. Это тот же код, что пойдёт в контуре, — поэтому проверяется
 * поднятием контекста, а не только вызовом метода: неверное имя свойства, профиль или разбор
 * списка ролей оставили бы выдачу «настроенной», но не работающей.
 */
@SpringBootTest(properties = {
    "app.security-baseline.enabled=true",
    "app.security-baseline.roles=WIRING_ROLE"
})
class InitialGrantIssuanceWiringTest {

    private static final String ROLE = "WIRING_ROLE";

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private InitialGrantIssuance issuance;

    @Autowired
    private AccessGrantRepository accessGrantRepository;

    @Test
    void theEnabledIssuanceRunsAtStartupForTheConfiguredRoles() {
        assertThat(issuance).isNotNull();
        List<AccessGrant> rows = accessGrantRepository.findAll().stream()
            .filter(row -> row.getSubjectType() == AccessGrant.SubjectType.ROLE
                && ROLE.equals(row.getSubjectKey()))
            .toList();
        assertThat(rows).as("CommandLineRunner выдал права роли из свойства").isNotEmpty();
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.getDimension())
                .isEqualTo(C5PermissionKeys.entityOperations(Nomenclature.class));
            assertThat(row.isCanUpdate()).isTrue();
        });
        assertThat(rows).anySatisfy(row -> assertThat(row.getDimension())
            .isEqualTo(C5PermissionKeys.entityRead(Nomenclature.class)));
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.getDimension())
                .as("административный минимум приходит свойством по умолчанию, а не только вызовом")
                .isEqualTo(C5PermissionKeys.action(UserPasswordChangePermission.PERMISSION_ID));
            assertThat(row.isCanUpdate()).as("EXECUTE решается флагом «изменение»").isTrue();
            assertThat(row.isCanRead()).isFalse();
            assertThat(row.isCanDelete()).isFalse();
        });
        assertThat(rows).anySatisfy(row -> assertThat(row.getDimension())
            .isEqualTo(C5PermissionKeys.action(UserRoleAssignmentPermission.PERMISSION_ID)));
    }
}
