package org.ipro.register;

import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsDimensionKind;
import org.ipro.rls.c5.C5Operation;
import org.ipro.rls.c5.C5OperationPermissionRegistry;
import org.ipro.rls.c5.C5PermissionDecisionService;
import org.ipro.rls.c5.C5PermissionDefinition;
import org.ipro.rls.c5.C5PermissionKeys;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.util.Objects;

/**
 * Именованные операции записи по ключу (ADR-0023): определения, требования и та же проверка для
 * команды и интерфейса.
 *
 * <p>Регистрация определения выдаёт возможность, а не грант: EXECUTE назначается отдельно, и
 * прежние пользователи его автоматически не получают. Возможность opt-in: тип без
 * {@code keyWriteAllowed} не приобретает ни операции, ни прав.</p>
 */
public final class RegisterKeyWritePermissions implements SmartInitializingSingleton {

    private final RegisterRegistry registers;
    private final C5OperationPermissionRegistry operations;
    private final RlsDimensionRegistry dimensions;
    private final C5PermissionDecisionService decisions;
    private final RlsCurrentUser currentUser;

    public RegisterKeyWritePermissions(RegisterRegistry registers, C5OperationPermissionRegistry operations,
            RlsDimensionRegistry dimensions, C5PermissionDecisionService decisions,
            RlsCurrentUser currentUser) {
        this.registers = Objects.requireNonNull(registers, "registers");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.dimensions = Objects.requireNonNull(dimensions, "dimensions");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser");
    }

    /** Stable action identity of the key write; registration never grants it. */
    public static String setPermissionId(Class<?> type) {
        return "REGISTER:" + type.getName() + ":SET_KEY";
    }

    /** Stable action identity of the key delete; separate from the write operation. */
    public static String deletePermissionId(Class<?> type) {
        return "REGISTER:" + type.getName() + ":DELETE_KEY";
    }

    @Override public void afterSingletonsInstantiated() {
        for (RegisterDescriptor descriptor : registers.descriptors()) {
            if (!descriptor.keyWriteAllowed()) continue;
            Class<?> type = descriptor.entityType();
            // Запись ресурсов по ключу — upsert: строка может быть создана или изменена,
            // поэтому обе операции обязаны существовать как отдельные решения до гранта.
            String entityKey = C5PermissionKeys.entityOperations(type);
            operations.requireSupports(entityKey, C5Operation.CREATE);
            operations.requireSupports(entityKey, C5Operation.UPDATE);
            operations.requireSupports(entityKey, C5Operation.DELETE);
            register(setPermissionId(type));
            register(deletePermissionId(type));
        }
    }

    private void register(String permissionId) {
        var definition = C5PermissionDefinition.action(permissionId);
        operations.register(definition);
        dimensions.registerDynamicDimension(definition.key(), RlsDimensionKind.CHECK_ONLY);
    }

    /** Проверка для интерфейса: то же решение, что у команды, и без выдачи полномочий. */
    public boolean canWriteKey(Class<?> type, boolean delete) {
        var descriptor = registers.find(type).orElse(null);
        String user = currentUser.username();
        if (descriptor == null || !descriptor.keyWriteAllowed() || user == null || user.isBlank()
                || "system".equals(user)) return false;
        String action = delete ? deletePermissionId(type) : setPermissionId(type);
        if (!decisions.decideAction(user, action).allowed()
                || !decisions.decideEntity(user, type, C5Operation.READ).allowed()) return false;
        if (delete) return decisions.decideEntity(user, type, C5Operation.DELETE).allowed();
        return decisions.decideEntity(user, type, C5Operation.CREATE).allowed()
            && decisions.decideEntity(user, type, C5Operation.UPDATE).allowed();
    }

    void requireSet(RegisterDescriptor descriptor) {
        requireDeclaration(descriptor);
        String user = currentUser.requireAuthenticatedUsername();
        decisions.requireAction(user, setPermissionId(descriptor.entityType()));
        decisions.requireEntity(user, descriptor.entityType(), C5Operation.READ);
        decisions.requireEntity(user, descriptor.entityType(), C5Operation.CREATE);
        decisions.requireEntity(user, descriptor.entityType(), C5Operation.UPDATE);
    }

    void requireDelete(RegisterDescriptor descriptor) {
        requireDeclaration(descriptor);
        String user = currentUser.requireAuthenticatedUsername();
        decisions.requireAction(user, deletePermissionId(descriptor.entityType()));
        decisions.requireEntity(user, descriptor.entityType(), C5Operation.READ);
        decisions.requireEntity(user, descriptor.entityType(), C5Operation.DELETE);
    }

    private void requireDeclaration(RegisterDescriptor descriptor) {
        if (!descriptor.keyWriteAllowed()) {
            throw new UnsupportedOperationException(
                "Этот регистр не разрешает запись по ключу: объявите keyWriteAllowed.");
        }
        if (descriptor.recorded()) {
            throw new UnsupportedOperationException(
                "Запись по ключу не применяется к подчинённому регистру: его строки принадлежат"
                    + " документу-регистратору.");
        }
    }
}
