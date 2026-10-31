package org.ipro.design.generation;

import java.util.Map;
import java.util.Objects;
import org.ipro.design.DraftProblem;

/** Addressable refusal of generation. All first-package generation diagnostics are errors. */
public record GenerationDiagnostic(Code code, DraftProblem.Target target, String messageKey,
                                   Map<String, String> parameters) {
    public enum Code {
        UNSUPPORTED_RECIPE,
        UNSUPPORTED_CONSTRUCT,
        STALE_CHECKED_STRUCTURE,
        UNREPRESENTABLE_REQUIRED_MODE,
        PROJECTION_UNREPRESENTABLE
    }

    public GenerationDiagnostic {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(messageKey, "messageKey");
        parameters = Map.copyOf(parameters);
        if (messageKey.isBlank()) throw new IllegalArgumentException("messageKey is required");
    }
}
