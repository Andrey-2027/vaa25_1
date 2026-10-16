package org.ipro.rls.c5;

import java.util.Objects;
import java.util.Set;

/**
 * One registered C5 permission definition: a stable key and the operations that key can express
 * (ADR-0017 §2, §3, §5).
 *
 * <p>A definition describes <b>which operation a key may decide</b>, not who holds it. Registering a
 * definition grants nobody access; grants stay in the grant store and are resolved only through
 * {@link C5PermissionDecisionService}.</p>
 *
 * <p>An entity has one fully qualified key and one complete operation set, including READ.
 * Reading and writing use independent flags of the same exact/global grant (P-U).</p>
 */
public record C5PermissionDefinition(String key, Set<C5Operation> operations) {

    private static final Set<C5Operation> ENTITY_OPERATIONS =
        Set.of(C5Operation.READ, C5Operation.CREATE, C5Operation.UPDATE, C5Operation.DELETE);

    private static final Set<String> KEY_PREFIXES = Set.of("ENTITY:", "ACTION:", "REST:");

    public C5PermissionDefinition {
        if (key != null && key.startsWith("ATTR:")) {
            if (!Set.of(C5Operation.READ).equals(operations)) {
                throw new IllegalArgumentException("Field permissions have been removed; use entity operations");
            }
            key = C5PermissionKeys.readKey(key);
        }
        key = requireKey(key);
        operations = operations == null ? Set.of() : Set.copyOf(operations);
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("C5 definition '" + key + "' declares no operation");
        }
        if (key.startsWith("REST:") && !operations.equals(Set.of(C5Operation.READ))) {
            throw new IllegalArgumentException("REST permissions express only READ");
        }
        if (key.startsWith("ENTITY:") && !ENTITY_OPERATIONS.containsAll(operations)) {
            throw new IllegalArgumentException("Entity permissions cannot express EXECUTE");
        }
        if (key.startsWith("ACTION:") && !operations.equals(Set.of(C5Operation.EXECUTE))) {
            throw new IllegalArgumentException("ACTION permissions express only EXECUTE");
        }
        if (operations.contains(C5Operation.EXECUTE) && operations.size() > 1) {
            throw new IllegalArgumentException("C5 named operation definition '" + key
                + "' must not carry other operations: " + operations);
        }
    }

    public boolean allows(C5Operation operation) {
        return operation != null && operations.contains(operation);
    }

    /** True only for the same key declared with a different operation set — a startup conflict. */
    public boolean conflictsWith(C5PermissionDefinition other) {
        return other != null && key.equals(other.key) && !operations.equals(other.operations);
    }

    /** Read definition of a canonical entity root; keeps the existing {@code ENTITY:<FQN>} key. */
    public static C5PermissionDefinition entityRead(Class<?> type) {
        return new C5PermissionDefinition(C5PermissionKeys.entityRead(type), Set.of(C5Operation.READ));
    }

    /** Complete entity definition derived from canonical read/write capabilities. */
    public static C5PermissionDefinition entityOperations(Class<?> type, Set<C5Operation> operations) {
        Objects.requireNonNull(type, "type must not be null");
        if (operations == null || operations.isEmpty()
                || !ENTITY_OPERATIONS.containsAll(operations)) {
            throw new IllegalArgumentException("Entity operation definition of " + type.getName()
                + " accepts only " + ENTITY_OPERATIONS + ": " + operations);
        }
        return new C5PermissionDefinition(C5PermissionKeys.entityOperations(type), operations);
    }

    /** Named owner operation; the permission id is the stable part of {@code ACTION:<permissionId>}. */
    public static C5PermissionDefinition action(String permissionId) {
        return new C5PermissionDefinition(C5PermissionKeys.action(permissionId), Set.of(C5Operation.EXECUTE));
    }

    private static String requireKey(String key) {
        Objects.requireNonNull(key, "key must not be null");
        if (key.isBlank() || !key.equals(key.trim())) {
            throw new IllegalArgumentException(
                "C5 permission key must be a non-blank name without outer spaces: '" + key + "'");
        }
        if (KEY_PREFIXES.stream().noneMatch(key::startsWith)) {
            throw new IllegalArgumentException("C5 permission key has no known prefix "
                + KEY_PREFIXES + ": '" + key + "'");
        }
        return key;
    }
}
