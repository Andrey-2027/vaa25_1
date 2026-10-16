package org.ip.application.access;

import java.io.Serializable;
import java.util.Objects;

/** Typed provenance for one flag. Labels are never parsed to locate the contributing role. */
public record PermissionContribution(Kind kind, SubjectRef subject, Long grantId,
                                     PermissionGrantAddress address, PermissionBinding.Flag flag,
                                     String explanation) implements Serializable {
    public enum Kind { STORED_GRANT, DRAFT_GRANT, SYSTEM_RULE }
    public PermissionContribution {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(flag, "flag");
        explanation = explanation == null ? "" : explanation;
        if (kind == Kind.SYSTEM_RULE) {
            if (subject != null || grantId != null || address != null || explanation.isBlank()) {
                throw new IllegalArgumentException("System provenance requires an explanation, not a fictitious role/grant");
            }
        } else {
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(address, "address");
            if (kind == Kind.STORED_GRANT && (grantId == null || grantId <= 0)) {
                throw new IllegalArgumentException("Stored provenance requires a grant id");
            }
            if (kind == Kind.DRAFT_GRANT && grantId != null) throw new IllegalArgumentException("New draft provenance has no stored id");
        }
    }
}
