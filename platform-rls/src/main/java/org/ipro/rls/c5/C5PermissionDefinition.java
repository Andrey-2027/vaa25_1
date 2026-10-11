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
 * <p>Two key spellings coexist by decision and are not unified in this slice: a read definition uses
 * the fully qualified {@code ENTITY:<FQN>} key, while an operation definition uses the existing
 * entity gate key {@code ENTITY:<SimpleName>} so already issued rows keep working (ADR-0017 §3 and
 * the known naming debt). Callers should not spell keys themselves: {@link #entityRead(Class)},
 * {@link #entityOperations(Class, Set)} and {@link #action(String)} are the only supported ways.</p>
 */
public record C5PermissionDefinition(String key, Set<C5Operation> operations) {

    /** Operations an entity definition may declare; read and named execution are separate families. */
    private static final Set<C5Operation> ENTITY_WRITE_OPERATIONS =
        Set.of(C5Operation.CREATE, C5Operation.UPDATE, C5Operation.DELETE);

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
        if (operations.contains(C5Operation.READ) && operations.size() > 1) {
            throw new IllegalArgumentException("C5 read definition '" + key
                + "' must not carry other operations: " + operations);
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

    /** Operation definition of an entity; keeps the existing entity gate key {@code ENTITY:<SimpleName>}. */
    public static C5PermissionDefinition entityOperations(Class<?> type, Set<C5Operation> operations) {
        Objects.requireNonNull(type, "type must not be null");
        if (operations == null || operations.isEmpty()
                || !ENTITY_WRITE_OPERATIONS.containsAll(operations)) {
            throw new IllegalArgumentException("Entity operation definition of " + type.getName()
                + " accepts only " + ENTITY_WRITE_OPERATIONS + ": " + operations);
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
