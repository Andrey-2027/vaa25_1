package org.ipro.register;

import org.ipro.rls.AccessService;
import org.ipro.rls.RlsCheckValue;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsDimensionKind;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.c5.*;
import org.springframework.beans.factory.SmartInitializingSingleton;

/** Per-register action definitions and the same operation decision used by command and UI. */
public final class RegisterKeyChangePermissions implements SmartInitializingSingleton {
    private final RegisterRegistry registers;
    private final C5OperationPermissionRegistry operations;
    private final RlsDimensionRegistry dimensions;
    private final C5PermissionDecisionService decisions;
    private final RlsCurrentUser currentUser;
    private final AccessService accessService;

    public RegisterKeyChangePermissions(RegisterRegistry registers, C5OperationPermissionRegistry operations,
            RlsDimensionRegistry dimensions, C5PermissionDecisionService decisions,
            RlsCurrentUser currentUser, AccessService accessService) {
        this.registers = registers;
        this.operations = operations;
        this.dimensions = dimensions;
        this.decisions = decisions;
        this.currentUser = currentUser;
        this.accessService = accessService;
    }

    /** Stable action identity. Registration does not grant it to any user or role. */
    public static String permissionId(Class<?> type) {
        return "REGISTER:" + type.getName() + ":CHANGE_KEY";
    }

    @Override public void afterSingletonsInstantiated() {
        for (RegisterDescriptor descriptor : registers.descriptors()) {
            if (descriptor.keyChangeAllowed()) {
                // Data modification is an existing operation; opting in must not invent generic CRUD rights.
                operations.requireSupports(C5PermissionKeys.entityOperations(descriptor.entityType()), C5Operation.UPDATE);
                var definition = C5PermissionDefinition.action(permissionId(descriptor.entityType()));
                operations.register(definition);
                dimensions.registerDynamicDimension(definition.key(), RlsDimensionKind.CHECK_ONLY);
            }
        }
    }

    public boolean canChangeKey(Class<?> type, Object row) {
        var descriptor = registers.find(type).orElse(null);
        String user = currentUser.username();
        if (descriptor == null || !descriptor.keyChangeAllowed() || user == null || user.isBlank()
                || "system".equals(user) || !type.isInstance(row)
                || !(row instanceof org.ipro.crud.BaseEntity entity) || entity.getId() == null
                || entity.getVersion() == null) return false;
        if (!decisions.decideAction(user, permissionId(type)).allowed()
                || !decisions.decideEntity(user, type, C5Operation.READ).allowed()
                || !decisions.decideEntity(user, type, C5Operation.UPDATE).allowed()) return false;
        // The recorder-owned type keeps its existing data/RLS write rights and no generic CRUD handle.
        var policy = dimensions.policyOf(type);
        var checks = policy.checksOf(row);
        for (String dimension : policy.dimensions().keySet()) {
            var values = checks.get(dimension);
            if (values == null || values.isEmpty()) return false;
            for (RlsCheckValue value : values) {
                if (value instanceof RlsCheckValue.NotApplicable) continue;
                if (!(value instanceof RlsCheckValue.Check check)
                        || !accessService.canUpdate(dimension, check.id(), user)) return false;
            }
        }
        return true;
    }

    void requireOperations(RegisterDescriptor descriptor) {
        if (!descriptor.keyChangeAllowed()) {
            throw new UnsupportedOperationException("Этот регистр не разрешает изменение ключа записи.");
        }
        String user = currentUser.requireAuthenticatedUsername();
        decisions.requireAction(user, permissionId(descriptor.entityType()));
        decisions.requireEntity(user, descriptor.entityType(), C5Operation.READ);
        decisions.requireEntity(user, descriptor.entityType(), C5Operation.UPDATE);
    }
}
