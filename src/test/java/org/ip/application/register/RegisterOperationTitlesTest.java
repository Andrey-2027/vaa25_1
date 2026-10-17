package org.ip.application.register;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.ip.application.catalog.NamedOperationOwner;
import org.ipro.register.RegisterDescriptor;
import org.ipro.register.RegisterKeyChangePermissions;
import org.ipro.register.RegisterKeyWritePermissions;
import org.ipro.register.RegisterMetadata;
import org.ipro.register.RegisterNamedOperations;
import org.ipro.register.RegisterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S5(а): в приложении остался <b>один</b> адаптер к каталогу прав, и он не держит текста операций —
 * подписи приходят из платформенного списка, собранного из декларации регистра (ADR-0024 §2.4).
 * Проверяется против настоящего {@link RegisterDescriptor}, а не против строки текста: если имя
 * перестанет читаться из декларации, тест это увидит.
 */
class RegisterOperationTitlesTest {

    private final RegisterRegistry registers = mock(RegisterRegistry.class);

    @Test
    void oneAdapterNamesEveryOperationOfADeclaredRegister() {
        when(registers.descriptors()).thenReturn(
            List.of(RegisterDescriptor.describe(KeyWriteFixture.class)));

        List<NamedOperationOwner> owners =
            new RegisterOperationTitles(new RegisterNamedOperations(registers)).namedOperations();

        assertThat(owners).extracting(NamedOperationOwner::permissionId).containsExactly(
            RegisterKeyChangePermissions.permissionId(KeyWriteFixture.class),
            RegisterKeyWritePermissions.setPermissionId(KeyWriteFixture.class),
            RegisterKeyWritePermissions.deletePermissionId(KeyWriteFixture.class));
        assertThat(owners).extracting(NamedOperationOwner::title).containsExactly(
            "Изменить ключ записи: Проверочный регистр приложения",
            "Записать по ключу: Проверочный регистр приложения",
            "Удалить по ключу: Проверочный регистр приложения");
    }

    @Test
    void registerWithoutNamedOperationsContributesNoTitle() {
        when(registers.descriptors()).thenReturn(
            List.of(RegisterDescriptor.describe(ClosedFixture.class)));

        assertThat(new RegisterOperationTitles(new RegisterNamedOperations(registers)).namedOperations())
            .as("адаптер называет операции объявленных регистров, а не регистрирует свои")
            .isEmpty();
    }

    @Entity(name = "AppOperationTitlesKeyWrite")
    @Table(name = "app_operation_titles_key_write", uniqueConstraints = @UniqueConstraint(
        name = "uk_app_operation_titles_key_write", columnNames = {"business_code"}))
    @RegisterMetadata(dimensions = "code", resources = "amount",
        keyChangeAllowed = true, keyWriteAllowed = true,
        displayName = "Проверочный регистр приложения")
    static class KeyWriteFixture {
        @Id Long id;
        @Column(name = "business_code", nullable = false) String code;
        int amount;
    }

    @Entity(name = "AppOperationTitlesClosed")
    @Table(name = "app_operation_titles_closed", uniqueConstraints = @UniqueConstraint(
        name = "uk_app_operation_titles_closed", columnNames = {"business_code"}))
    @RegisterMetadata(dimensions = "code", resources = "amount")
    static class ClosedFixture {
        @Id Long id;
        @Column(name = "business_code", nullable = false, updatable = false) String code;
        int amount;
    }
}
