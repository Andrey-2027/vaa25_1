package org.ip.application.access;

import java.io.Serializable;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.ip.application.access.PermissionBinding.Flag;

/** Explicit dirty flags for one subject; not replacement of a dimension or a visible page. */
public record PermissionPatch(SubjectRef subject, List<Change> changes) implements Serializable {
    public record Flags(boolean read, boolean update, boolean delete) implements Serializable {
        /** Dirty flags replace only their own values in the shared storage row. */
        public Flags apply(Map<Flag, Boolean> dirtyFlags) {
            var changes = Map.copyOf(dirtyFlags);
            return new Flags(changes.getOrDefault(Flag.READ, read),
                changes.getOrDefault(Flag.UPDATE, update), changes.getOrDefault(Flag.DELETE, delete));
        }
    }
    public record Snapshot(long grantId, long version, Flags flags) implements Serializable {
        public Snapshot {
            if (grantId <= 0 || version < 0) throw new IllegalArgumentException("Persisted id/version is required");
            Objects.requireNonNull(flags, "flags");
        }
    }
    public record Change(PermissionGrantAddress address, Map<Flag, Boolean> desiredFlags,
                         List<Snapshot> expectedRows) implements Serializable {
        public Change {
            Objects.requireNonNull(address, "address");
            desiredFlags = Map.copyOf(desiredFlags);
            expectedRows = List.copyOf(expectedRows);
            if (desiredFlags.isEmpty()) throw new IllegalArgumentException("A change must name dirty flags");
            if (address.key().equals("*") && address.valueId() != null) {
                throw new IllegalArgumentException("Historical value-scoped wildcard needs diagnostics, not normal editing");
            }
            var ids = new HashSet<Long>();
            for (var snapshot : expectedRows) if (!ids.add(snapshot.grantId())) throw new IllegalArgumentException("Duplicate expected grant");
        }
    }

    public PermissionPatch {
        Objects.requireNonNull(subject, "subject");
        changes = List.copyOf(changes);
        var addresses = new HashSet<StorageCoordinate>();
        for (var change : changes) {
            var coordinate = new StorageCoordinate(change.address().key(), change.address().valueId());
            if (!addresses.add(coordinate)) throw new IllegalArgumentException("Duplicate physical grant address");
        }
    }

    private record StorageCoordinate(String key, Long valueId) { }
}
