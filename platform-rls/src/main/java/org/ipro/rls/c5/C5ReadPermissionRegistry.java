package org.ipro.rls.c5;

import java.util.Objects;
import java.util.Set;

/**
 * Read projection of the common C5 definition registry (P-U, F-REST-READ-3).
 *
 * <p>The entity registrar declares complete read/write capability sets in the common registry;
 * API registrars declare read-only keys through this projection. Explicit registration recognises a C5 read
 * permission: a key prefix such as {@code ENTITY:}/{@code REST:}, the CHECK_ONLY RLS kind
 * or a {@code "*"} wildcard row must never be treated as such.</p>
 *
 * <p>Registering a definition grants nobody access; grants stay in the grant store and are
 * resolved only through {@link C5ReadDecisionService}.</p>
 */
public class C5ReadPermissionRegistry {

    private final C5OperationPermissionRegistry definitions;

    public C5ReadPermissionRegistry() {
        this(new C5OperationPermissionRegistry());
    }

    /** Read projection of the common registry; does not own a second entity definition. */
    public C5ReadPermissionRegistry(C5OperationPermissionRegistry definitions) {
        this.definitions = Objects.requireNonNull(definitions, "definitions");
    }

    /** Registers one C5 read permission key; repeated registration is idempotent. */
    public void registerReadKey(String key) {
        String exact = requireReadKey(C5PermissionKeys.readKey(key));
        if (!isRegisteredReadKey(exact)) {
            definitions.register(new C5PermissionDefinition(exact, Set.of(C5Operation.READ)));
        }
    }

    /** True only for keys explicitly registered through {@link #registerReadKey(String)}. */
    public boolean isRegisteredReadKey(String dimension) {
        return dimension != null && !dimension.startsWith("ATTR:")
            && definitions.operationsOf(dimension).contains(C5Operation.READ);
    }

    /** Immutable snapshot of the registered keys, sorted. */
    public Set<String> registeredKeys() {
        return definitions.definitions().stream().filter(definition -> definition.allows(C5Operation.READ))
            .map(C5PermissionDefinition::key).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String requireReadKey(String key) {
        Objects.requireNonNull(key, "key must not be null");
        if (key.isBlank() || !key.equals(key.trim())) {
            throw new IllegalArgumentException("C5 read permission key must be a non-blank name without outer spaces: '" + key + "'");
        }
        return key;
    }
}
