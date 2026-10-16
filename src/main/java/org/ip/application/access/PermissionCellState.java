package org.ip.application.access;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/** Checked immutable state calculated by P2; P4 overlays the active subject's draft. */
public record PermissionCellState(PermissionBinding binding, PermissionGrantAddress address,
                                  boolean direct, boolean effective, boolean editable, boolean dirty,
                                  List<PermissionContribution> sources, String explanation) implements Serializable {
    public PermissionCellState {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(address, "address");
        sources = List.copyOf(sources);
        explanation = explanation == null ? "" : explanation;
        if (binding.authority() != address.authority() || !binding.key().equals(address.key())) {
            throw new IllegalArgumentException("Cell binding/address mismatch");
        }
        if (direct && !effective) throw new IllegalArgumentException("A direct allow cannot produce an effective deny");
        if (!effective && !sources.isEmpty()) throw new IllegalArgumentException("Denied cell cannot carry allowing sources");
        if (effective && sources.isEmpty() && explanation.isBlank()) {
            throw new IllegalArgumentException("Effective permission requires provenance or a policy explanation");
        }
        for (var source : sources) {
            if (source.flag() != binding.flag()) throw new IllegalArgumentException("Source belongs to a different flag");
            if (source.kind() == PermissionContribution.Kind.SYSTEM_RULE) continue;
            var origin = source.address();
            if (origin.authority() != address.authority()) throw new IllegalArgumentException("Source belongs to a different decision owner");
            boolean wildcard = address.authority() == PermissionBinding.Authority.RLS && origin.key().equals("*");
            if (!wildcard && !origin.key().equals(address.key())) throw new IllegalArgumentException("Source belongs to another permission");
            if (address.valueId() != null && origin.valueId() != null && !address.valueId().equals(origin.valueId())) {
                throw new IllegalArgumentException("Source belongs to another scope value");
            }
        }
    }

    /** All inherited roles once, even when physical grants are duplicated or overlap in scale. */
    public List<SubjectRef> grantingRoles(SubjectRef activeSubject) {
        return sources.stream().map(PermissionContribution::subject).filter(Objects::nonNull)
            .filter(subject -> subject.type() == org.ipro.rls.AccessGrant.SubjectType.ROLE)
            .filter(subject -> !subject.identity().equals(activeSubject.identity()))
            .distinct().toList();
    }
}
