package org.ipro.rls.c5;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Explicit registry of C5 operation definitions (ADR-0017 §5).
 *
 * <p>Registrars declare the exact keys they own at startup: entity {@code CREATE}/{@code UPDATE}/
 * {@code DELETE} are derived from canonical capabilities, named operations are declared by the
 * subsystem that owns them. The registry is the only way to recognise a C5 operation key, so a key
 * prefix, an RLS dimension name or a {@code "*"} wildcard row can never be treated as one.</p>
 *
 * <p>Registration is idempotent for an identical operation set and fails fast on a conflict: the
 * same key declared with different operations is a configuration error, not a silent override. An
 * unknown key or an operation the definition does not declare is refused through
 * {@link #requireSupports(String, C5Operation)} before any grant is read — never turned into an
 * allow.</p>
 */
public class C5OperationPermissionRegistry {

    private final ConcurrentHashMap<String, Set<C5Operation>> definitions = new ConcurrentHashMap<>();

    /** Registers one operation definition; repeated registration with the same set is idempotent. */
    public void register(C5PermissionDefinition definition) {
        Objects.requireNonNull(definition, "definition must not be null");
        Set<C5Operation> operations = Set.copyOf(definition.operations());
        Set<C5Operation> existing = definitions.putIfAbsent(definition.key(), operations);
        if (existing != null && !existing.equals(operations)) {
            throw new C5PermissionConfigurationException("C5 definition conflict for '"
                + definition.key() + "': already registered as " + existing
                + ", requested as " + operations);
        }
    }

    /** True only for keys explicitly registered through {@link #register(C5PermissionDefinition)}. */
    public boolean isRegistered(String key) {
        return key != null && definitions.containsKey(key);
    }

    /**
     * Operations declared by the key, or empty when no registrar declared it.
     *
     * <p>This is the query for paths that <b>display and issue</b> rights: the admin screen and the
     * issuance service must name a key and offer exactly what its definition expresses, so an
     * unknown key there is the question "is there a definition", not a configuration error. The
     * access decision still asks {@link #requireSupports(String, C5Operation)} and fails on an
     * unregistered key: displaying and issuing must not replace deciding.</p>
     */
    public Set<C5Operation> operationsOf(String key) {
        if (key == null) {
            return Set.of();
        }
        Set<C5Operation> operations = definitions.get(key);
        return operations == null ? Set.of() : operations;
    }

    /** Operations declared by the key; an unknown definition is a configuration error, not a denial. */
    public Set<C5Operation> requireOperations(String key) {
        requireKey(key);
        Set<C5Operation> operations = definitions.get(key);
        if (operations == null) {
            throw new C5PermissionConfigurationException("Unknown C5 permission definition '"
                + key + "': no registrar declared it, so no subject can hold it");
        }
        return operations;
    }

    /** Verifies that the definition exists and declares the operation before any grant is read. */
    public void requireSupports(String key, C5Operation operation) {
        Objects.requireNonNull(operation, "operation must not be null");
        Set<C5Operation> operations = requireOperations(key);
        if (!operations.contains(operation)) {
            throw new C5PermissionConfigurationException("C5 definition '" + key
                + "' declares " + operations + " and cannot decide " + operation);
        }
    }

    /** Immutable snapshot of the registered definitions, sorted by key. */
    public Set<C5PermissionDefinition> definitions() {
        LinkedHashSet<C5PermissionDefinition> snapshot = definitions.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> new C5PermissionDefinition(entry.getKey(), entry.getValue()))
            .collect(Collectors.toCollection(LinkedHashSet::new));
        return Collections.unmodifiableSet(snapshot);
    }

    public int size() {
        return definitions.size();
    }

    private static void requireKey(String key) {
        Objects.requireNonNull(key, "key must not be null");
        if (key.isBlank()) {
            throw new IllegalArgumentException("C5 permission key must not be blank");
        }
    }
}
