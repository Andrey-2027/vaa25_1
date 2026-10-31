package org.ipro.design;

import java.util.Objects;

public record ReferenceTargetDescriptor(String typeId, String caption, String exposure, String identityType,
                                        Fact representation, Fact selection, Eligibility eligibility, String reason,
                                        String sourceTypeName) {
    public ReferenceTargetDescriptor(String typeId, String caption, String exposure, String identityType,
            Fact representation, Fact selection, Eligibility eligibility, String reason) {
        this(typeId, caption, exposure, identityType, representation, selection, eligibility, reason,
                typeId.replace('$', '.'));
    }
    public enum Fact { YES, NO, UNKNOWN }
    public enum Eligibility { SUPPORTED, UNSUPPORTED, UNKNOWN }
    public ReferenceTargetDescriptor {
        Objects.requireNonNull(typeId); Objects.requireNonNull(caption); Objects.requireNonNull(exposure);
        Objects.requireNonNull(identityType); Objects.requireNonNull(representation); Objects.requireNonNull(selection);
        Objects.requireNonNull(eligibility); Objects.requireNonNull(reason);
        if (sourceTypeName == null) sourceTypeName = typeId.replace('$', '.');
    }
    public boolean supported() {
        return eligibility == Eligibility.SUPPORTED && exposure.equals("STANDARD_ROOT") && identityType.equals("java.lang.Long")
                && representation == Fact.YES && selection == Fact.YES;
    }
}
