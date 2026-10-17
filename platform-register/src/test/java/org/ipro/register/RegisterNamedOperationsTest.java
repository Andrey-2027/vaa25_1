package org.ipro.register;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ADR-0024 §2.4 — подписи именованных операций регистра выводятся из декларации и отдаются как
 * данные. Проверяется форма подписи и то, что возможность остаётся opt-in: регистр без
 * {@code keyChangeAllowed}/{@code keyWriteAllowed} не даёт ни одной записи, а тип с обоими флагами
 * даёт ровно три операции — те же идентификаторы, что регистрируют таблицы прав, иначе подпись
 * относилась бы к операции, которой нет.
 */
class RegisterNamedOperationsTest {

    private final RegisterRegistry registers = mock(RegisterRegistry.class);

    @Test
    void titlesComeFromTheDeclaredNameAndKeepTheStableIdentifiers() {
        when(registers.descriptors()).thenReturn(List.of(
            RegisterDescriptor.describe(KeyWriteRegister.class)));

        assertThat(new RegisterNamedOperations(registers).operations())
            .as("три операции одного регистра названы единообразно и не текстом приложения")
            .extracting(RegisterNamedOperations.Operation::permissionId, RegisterNamedOperations.Operation::title)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(
                    RegisterKeyChangePermissions.permissionId(KeyWriteRegister.class),
                    "Изменить ключ записи: Проверочный регистр цен"),
                org.assertj.core.groups.Tuple.tuple(
                    RegisterKeyWritePermissions.setPermissionId(KeyWriteRegister.class),
                    "Записать по ключу: Проверочный регистр цен"),
                org.assertj.core.groups.Tuple.tuple(
                    RegisterKeyWritePermissions.deletePermissionId(KeyWriteRegister.class),
                    "Удалить по ключу: Проверочный регистр цен"));
    }

    @Test
    void keyChangeOnlyRegisterGetsExactlyOneTitledOperation() {
        when(registers.descriptors()).thenReturn(List.of(
            RegisterDescriptor.describe(KeyChangeOnlyRegister.class)));

        assertThat(new RegisterNamedOperations(registers).operations())
            .extracting(RegisterNamedOperations.Operation::title)
            .containsExactly("Изменить ключ записи: Проверочный регистр смены ключа");
    }

    @Test
    void registerWithoutNamedOperationsContributesNothing() {
        when(registers.descriptors()).thenReturn(List.of(
            RegisterDescriptor.describe(ClosedRegister.class)));

        assertThat(new RegisterNamedOperations(registers).operations())
            .as("подпись появляется из opt-in, а не из факта объявления регистра")
            .isEmpty();
    }

    @Test
    void theListIsAnImmutableSnapshot() {
        when(registers.descriptors()).thenReturn(List.of(
            RegisterDescriptor.describe(KeyChangeOnlyRegister.class)));
        List<RegisterNamedOperations.Operation> operations =
            new RegisterNamedOperations(registers).operations();

        assertThat(operations).isUnmodifiable();
    }

    @Test
    void descriptorPublishesTheDeclaredName() {
        assertThat(RegisterDescriptor.describe(KeyChangeOnlyRegister.class).displayName())
            .isEqualTo("Проверочный регистр смены ключа");
        assertThat(RegisterDescriptor.describe(ClosedRegister.class).displayName())
            .as("пустое имя — «не объявлено», а не «имя не читается»")
            .isEmpty();
    }

    @Entity(name = "NamedOperationsKeyWrite")
    @Table(name = "named_operations_key_write", uniqueConstraints = @UniqueConstraint(
        name = "uk_named_operations_key_write", columnNames = {"business_code"}))
    @RegisterMetadata(dimensions = "code", resources = "amount",
        keyChangeAllowed = true, keyWriteAllowed = true,
        displayName = "Проверочный регистр цен")
    static class KeyWriteRegister {
        @Id Long id;
        @Column(name = "business_code", nullable = false) String code;
        int amount;
    }

    @Entity(name = "NamedOperationsKeyChange")
    @Table(name = "named_operations_key_change", uniqueConstraints = @UniqueConstraint(
        name = "uk_named_operations_key_change", columnNames = {"business_code"}))
    @RegisterMetadata(dimensions = "code", resources = "amount", keyChangeAllowed = true,
        displayName = "Проверочный регистр смены ключа")
    static class KeyChangeOnlyRegister {
        @Id Long id;
        @Column(name = "business_code", nullable = false) String code;
        int amount;
    }

    @Entity(name = "NamedOperationsClosed")
    @Table(name = "named_operations_closed", uniqueConstraints = @UniqueConstraint(
        name = "uk_named_operations_closed", columnNames = {"business_code"}))
    @RegisterMetadata(dimensions = "code", resources = "amount")
    static class ClosedRegister {
        @Id Long id;
        @Column(name = "business_code", nullable = false, updatable = false) String code;
        int amount;
    }
}
