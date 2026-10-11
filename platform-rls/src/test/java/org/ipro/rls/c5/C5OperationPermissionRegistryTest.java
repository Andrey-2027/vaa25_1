package org.ipro.rls.c5;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-0017 §5: a C5 operation key exists only because a registrar declared it. */
class C5OperationPermissionRegistryTest {

    private final C5OperationPermissionRegistry registry = new C5OperationPermissionRegistry();

    @Test
    void registersEntityOperationsUnderTheExistingGateKey() {
        registry.register(C5PermissionDefinition.entityOperations(String.class,
            Set.of(C5Operation.CREATE, C5Operation.UPDATE, C5Operation.DELETE)));

        assertThat(C5PermissionKeys.entityOperations(String.class)).isEqualTo("ENTITY:String");
        assertThat(registry.isRegistered("ENTITY:String")).isTrue();
        assertThat(registry.requireOperations("ENTITY:String"))
            .containsExactlyInAnyOrder(C5Operation.CREATE, C5Operation.UPDATE, C5Operation.DELETE);
    }

    @Test
    void readAndOperationDefinitionsAreSeparateSpellingsOfTheSameType() {
        registry.register(C5PermissionDefinition.entityRead(String.class));
        registry.register(C5PermissionDefinition.entityOperations(String.class, Set.of(C5Operation.UPDATE)));

        assertThat(registry.definitions()).extracting(C5PermissionDefinition::key)
            .containsExactlyInAnyOrder(C5PermissionKeys.entityRead(String.class),
                C5PermissionKeys.entityOperations(String.class));
        assertThat(registry.requireOperations(C5PermissionKeys.entityRead(String.class)))
            .containsExactly(C5Operation.READ);
    }

    @Test
    void repeatedIdenticalRegistrationIsIdempotent() {
        registry.register(C5PermissionDefinition.action("RegisterWrite"));
        registry.register(C5PermissionDefinition.action("RegisterWrite"));

        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentOperationsIsAConfigurationError() {
        registry.register(C5PermissionDefinition.entityOperations(String.class, Set.of(C5Operation.CREATE)));

        assertThatThrownBy(() -> registry.register(
            C5PermissionDefinition.entityOperations(String.class, Set.of(C5Operation.CREATE, C5Operation.DELETE))))
            .isInstanceOf(C5PermissionConfigurationException.class)
            .hasMessageContaining("ENTITY:String")
            .hasMessageContaining("conflict");
    }

    /**
     * Display and issuance ask without throwing: an unknown key there is the question "is there a
     * definition", while the decision path keeps failing closed on it.
     */
    @Test
    void declaredOperationsAreAvailableWithoutThrowingToDisplayAndIssuancePaths() {
        registry.register(C5PermissionDefinition.action("RegisterWrite"));

        assertThat(registry.operationsOf("ACTION:RegisterWrite"))
            .containsExactly(C5Operation.EXECUTE);
        assertThat(registry.operationsOf("ACTION:NeverDeclared")).isEmpty();
        assertThat(registry.operationsOf(null)).isEmpty();
        assertThatThrownBy(() -> registry.requireOperations("ACTION:NeverDeclared"))
            .as("показ и выдача не заменяют решение: тот же ключ в решении падает")
            .isInstanceOf(C5PermissionConfigurationException.class);
    }

    @Test
    void unknownKeyIsAConfigurationErrorNotADenial() {
        assertThatThrownBy(() -> registry.requireOperations("ENTITY:NeverDeclared"))
            .isInstanceOf(C5PermissionConfigurationException.class)
            .hasMessageContaining("Unknown C5 permission definition");
        assertThat(registry.isRegistered("ENTITY:NeverDeclared")).isFalse();
    }

    @Test
    void operationTheDefinitionDoesNotDeclareIsRefusedBeforeAnyGrantIsRead() {
        registry.register(C5PermissionDefinition.entityOperations(String.class, Set.of(C5Operation.CREATE)));

        assertThatThrownBy(() -> registry.requireSupports("ENTITY:String", C5Operation.DELETE))
            .isInstanceOf(C5PermissionConfigurationException.class)
            .hasMessageContaining("cannot decide DELETE");
        assertThat(registry.isRegistered("ENTITY:NeverDeclared")).isFalse();
        assertThat(registry.isRegistered("*")).isFalse();
    }

    @Test
    void definitionsSnapshotIsSortedAndImmutable() {
        registry.register(C5PermissionDefinition.action("Zeta"));
        registry.register(C5PermissionDefinition.action("Alpha"));

        assertThat(registry.definitions()).extracting(C5PermissionDefinition::key)
            .containsExactly("ACTION:Alpha", "ACTION:Zeta");
        assertThatThrownBy(() -> registry.definitions().clear())
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void definitionRefusesAKeyWithoutAKnownPrefixOrWithoutAnOperation() {
        assertThatThrownBy(() -> new C5PermissionDefinition("Foo:Bar", Set.of(C5Operation.READ)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no known prefix");
        assertThatThrownBy(() -> new C5PermissionDefinition("ENTITY:X", Set.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("declares no operation");
        assertThatThrownBy(() -> new C5PermissionDefinition("ENTITY:X",
            Set.of(C5Operation.READ, C5Operation.UPDATE)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not carry other operations");
    }

    @Test
    void entityOperationDefinitionAcceptsOnlyWriteOperations() {
        assertThatThrownBy(() -> C5PermissionDefinition.entityOperations(String.class,
            Set.of(C5Operation.READ)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("accepts only");
        assertThatThrownBy(() -> C5PermissionDefinition.entityOperations(String.class,
            Set.of(C5Operation.EXECUTE)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
