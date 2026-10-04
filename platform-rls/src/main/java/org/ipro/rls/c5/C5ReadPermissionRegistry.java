package org.ipro.rls.c5;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * Explicit registry of C5 read permission definitions (F-REST-READ-3, З2).
 *
 * <p>Registrars ({@code C5EntityReadPermissionRegistrar} for canonical entity roots,
 * {@code C5RestOperationRegistrar} for API operations and attribute paths) register the
 * exact keys they declare at startup. The registry is the only way to recognise a C5 read
 * permission: a key prefix such as {@code ENTITY:}/{@code REST:}, the CHECK_ONLY RLS kind
 * or a {@code "*"} wildcard row must never be treated as such.</p>
 *
 * <p>Registering a definition grants nobody access; grants stay in the grant store and are
 * resolved only through {@link C5ReadDecisionService}.</p>
 */
public class C5ReadPermissionRegistry {

    private final Set<String> registeredKeys = new ConcurrentSkipListSet<>();

    /** Registers one C5 read permission key; repeated registration is idempotent. */
    public void registerReadKey(String key) {
        registeredKeys.add(requireReadKey(key));
    }

    /** True only for keys explicitly registered through {@link #registerReadKey(String)}. */
    public boolean isRegisteredReadKey(String dimension) {
        return dimension != null && registeredKeys.contains(dimension);
    }

    /** Immutable snapshot of the registered keys, sorted. */
    public Set<String> registeredKeys() {
        return Set.copyOf(registeredKeys);
    }

    private static String requireReadKey(String key) {
        Objects.requireNonNull(key, "key must not be null");
        if (key.isBlank() || !key.equals(key.trim())) {
            throw new IllegalArgumentException("C5 read permission key must be a non-blank name without outer spaces: '" + key + "'");
        }
        return key;
    }
}
