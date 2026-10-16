package org.ip.application.access;

import java.io.Serializable;
import java.util.Objects;
import java.util.Set;
import org.ipro.rls.c5.C5Operation;

/** One actual grant flag, with its decision owner and every operation coupled to that flag. */
public record PermissionBinding(String key, Authority authority, Flag flag,
                                Set<C5Operation> operations) implements Serializable {
    public enum Authority { C5, RLS }
    public enum Flag { READ, UPDATE, DELETE }

    public PermissionBinding {
        key = requireKey(key);
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(flag, "flag");
        operations = Set.copyOf(operations);
        if (operations.isEmpty()) throw new IllegalArgumentException("An operative binding requires operations");
        Set<C5Operation> expressible = switch (flag) {
            case READ -> Set.of(C5Operation.READ);
            case UPDATE -> Set.of(C5Operation.CREATE, C5Operation.UPDATE, C5Operation.EXECUTE);
            case DELETE -> Set.of(C5Operation.DELETE);
        };
        if (!expressible.containsAll(operations)) throw new IllegalArgumentException("Flag/operation mismatch");
        if (key.startsWith("ATTR:")) throw new IllegalArgumentException("Field permissions are not assignable");
        if (authority != Authority.RLS && key.equals("*")) {
            throw new IllegalArgumentException("RLS wildcard does not grant C5 permissions");
        }
    }

    static String requireKey(String key) {
        Objects.requireNonNull(key, "key");
        if (key.isBlank() || !key.equals(key.trim())) throw new IllegalArgumentException("A non-blank exact key is required");
        return key;
    }
}
