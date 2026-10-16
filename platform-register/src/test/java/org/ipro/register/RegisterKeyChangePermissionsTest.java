package org.ipro.register;

import org.ipro.crud.BaseEntity;
import org.ipro.rls.*;
import org.ipro.rls.c5.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RegisterKeyChangePermissionsTest {
    private RegisterRegistry registers;
    private RlsDimensionRegistry dimensions;
    private C5OperationPermissionRegistry operations;
    private C5PermissionDecisionService decisions;
    private AccessService access;
    private RegisterKeyChangePermissions permissions;
    private RegisterDescriptor descriptor;
    private RegisterKeyChangesTest.KeyRow row;

    @BeforeEach void setUp() {
        descriptor = RegisterDescriptor.describe(RegisterKeyChangesTest.KeyRow.class);
        registers = mock(RegisterRegistry.class);
        when(registers.descriptors()).thenReturn(List.of(descriptor));
        when(registers.find(RegisterKeyChangesTest.KeyRow.class)).thenReturn(Optional.of(descriptor));
        dimensions = mock(RlsDimensionRegistry.class);
        RlsPolicyDescriptor policy = mock(RlsPolicyDescriptor.class);
        when(dimensions.policyOf(any())).thenReturn(policy);
        when(policy.dimensions()).thenReturn(Map.of());
        when(policy.checksOf(any())).thenReturn(Map.of());
        access = mock(AccessService.class);
        operations = new C5OperationPermissionRegistry();
        operations.register(C5PermissionDefinition.entityOperations(descriptor.entityType(), Set.of(C5Operation.UPDATE)));
        decisions = mock(C5PermissionDecisionService.class);
        when(decisions.decideAction(anyString(), anyString())).thenAnswer(invocation ->
            C5PermissionDecision.permitted(C5PermissionKeys.action(invocation.getArgument(1)), C5Operation.EXECUTE, List.of("test")));
        when(decisions.decideEntity(anyString(), any(), any())).thenAnswer(invocation ->
            C5PermissionDecision.permitted("ENTITY:test", invocation.getArgument(2), List.of("test")));
        permissions = new RegisterKeyChangePermissions(registers, operations, dimensions, decisions, () -> "key-user", access);
        row = new RegisterKeyChangesTest.KeyRow(); row.setId(7L); row.setVersion(2L);
    }

    @Test void declaresOnlyTheOptInActionWithoutIssuingAnyGrant() {
        permissions.afterSingletonsInstantiated();
        String key = C5PermissionKeys.action(RegisterKeyChangePermissions.permissionId(descriptor.entityType()));
        assertThat(operations.operationsOf(key)).containsExactly(C5Operation.EXECUTE);
        verify(dimensions).registerDynamicDimension(key, RlsDimensionKind.CHECK_ONLY);
        verifyNoInteractions(decisions, access);
        assertThat(operations.definitions()).noneMatch(value -> value.key().startsWith("ATTR:"));
    }

    @Test void uiDecisionRequiresReadDataUpdateAndNamedAction() {
        assertThat(permissions.canChangeKey(descriptor.entityType(), row)).isTrue();
        verify(decisions).decideAction("key-user", RegisterKeyChangePermissions.permissionId(descriptor.entityType()));
        verify(decisions).decideEntity("key-user", descriptor.entityType(), C5Operation.READ);
        verify(decisions).decideEntity("key-user", descriptor.entityType(), C5Operation.UPDATE);
    }

    @Test void readDenialClosesTheUiAction() {
        when(decisions.decideEntity("key-user", descriptor.entityType(), C5Operation.READ)).thenReturn(
            C5PermissionDecision.denied(C5DecisionReason.ENTITY_PERMISSION_DENIED, "ENTITY:test", C5Operation.READ));
        assertThat(permissions.canChangeKey(descriptor.entityType(), row)).isFalse();
    }

    @Test void updateDenialClosesTheUiAction() {
        when(decisions.decideEntity("key-user", descriptor.entityType(), C5Operation.UPDATE)).thenReturn(
            C5PermissionDecision.denied(C5DecisionReason.ENTITY_PERMISSION_DENIED, "ENTITY:test", C5Operation.UPDATE));
        assertThat(permissions.canChangeKey(descriptor.entityType(), row)).isFalse();
    }

    @Test void aNewRowCannotOfferKeyChange() {
        assertThat(permissions.canChangeKey(descriptor.entityType(), new RegisterKeyChangesTest.KeyRow())).isFalse();
        verifyNoInteractions(decisions);
    }

    @Test void commandUsesTheSameC5OperationsAndNoFieldPermissions() {
        permissions.requireOperations(descriptor);
        verify(decisions).requireAction("key-user", RegisterKeyChangePermissions.permissionId(descriptor.entityType()));
        verify(decisions).requireEntity("key-user", descriptor.entityType(), C5Operation.READ);
        verify(decisions).requireEntity("key-user", descriptor.entityType(), C5Operation.UPDATE);
        verifyNoMoreInteractions(decisions);
    }

    @Test void capabilityOffRejectsBeforePermissions() {
        assertThatThrownBy(() -> permissions.requireOperations(RegisterDescriptor.describe(RegisterKeyChangesTest.ClosedRow.class)))
            .isInstanceOf(UnsupportedOperationException.class);
        verifyNoInteractions(decisions);
    }
}
