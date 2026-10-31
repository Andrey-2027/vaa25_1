package org.ipro.design;

import java.util.*;

public record DraftProblem(Severity severity, Code code, Target target, String messageKey, Map<String, String> parameters) {
    public enum Severity { ERROR, WARNING }
    public enum Code {
        VALUE_REQUIRED, INVALID_JAVA_IDENTIFIER, JAVA_KEYWORD, INVALID_PACKAGE, DUPLICATE_NAME, RESERVED_FIELD,
        DUPLICATE_TYPE_NAME, INVALID_STORAGE_NAME, DUPLICATE_STORAGE_NAME, INVALID_CONSTRAINT, INAPPLICABLE_CONSTRAINT,
        UNSUPPORTED_FIELD_TYPE, REFERENCE_REQUIRED, REFERENCE_UNAVAILABLE, INVALID_APPLICATION,
        SUBSYSTEM_UNAVAILABLE, ICON_UNAVAILABLE, PROJECT_FACT_UNKNOWN, UNSUPPORTED_VERSION, DUPLICATE_ID,
        LIMIT_EXCEEDED, UI_REQUIRED_MISMATCH, STYLE_CONVENTION, RECIPE_DRAFT_UNSUPPORTED,
        RECIPE_PREREQUISITE_UNAVAILABLE, APPLICATION_PROJECTION_UNREPRESENTABLE, UI_REQUIRED_SERVER_OPTIONAL,
        OWNER_UNSUPPORTED_RECIPE
        , REGISTER_ROLE_MISSING, REGISTER_ROLE_DUPLICATE, REGISTER_ROLE_UNKNOWN,
        REGISTER_SECTION_UNSUPPORTED, REGISTER_PERIOD_UNSUPPORTED, REGISTER_WRITE_PROFILE_UNSUPPORTED,
        REGISTER_FIELD_ROLE_UNSUPPORTED, REGISTER_DIMENSION_REQUIRED
    }
    public record Target(UUID draftId, UUID sectionId, UUID fieldId, String propertyKey) {
        public Target { Objects.requireNonNull(draftId); Objects.requireNonNull(propertyKey); }
    }
    public DraftProblem {
        Objects.requireNonNull(severity); Objects.requireNonNull(code); Objects.requireNonNull(target);
        Objects.requireNonNull(messageKey); parameters = Map.copyOf(parameters);
    }
}
