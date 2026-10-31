package org.ipro.design.generation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Exactly one outcome: a plan without diagnostics or diagnostics without a partial plan. */
public record GenerationResult(Optional<GenerationPlan> plan, List<GenerationDiagnostic> diagnostics) {
    public GenerationResult {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(diagnostics, "diagnostics");
        diagnostics = List.copyOf(diagnostics);
        if (plan.isPresent() != diagnostics.isEmpty())
            throw new IllegalArgumentException("Either a plan or a non-empty diagnostics list is required");
    }

    public static GenerationResult generated(GenerationPlan plan) {
        return new GenerationResult(Optional.of(Objects.requireNonNull(plan, "plan")), List.of());
    }

    public static GenerationResult rejected(List<GenerationDiagnostic> diagnostics) {
        Objects.requireNonNull(diagnostics, "diagnostics");
        return new GenerationResult(Optional.empty(), diagnostics);
    }

    public boolean succeeded() {
        return plan.isPresent();
    }
}
