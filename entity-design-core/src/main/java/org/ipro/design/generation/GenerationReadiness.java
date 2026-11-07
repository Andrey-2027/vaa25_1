package org.ipro.design.generation;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.ipro.design.CheckedStructure;
import org.ipro.design.DraftProblem;
import org.ipro.design.RecipeCatalog;
import org.ipro.design.RecipeRef;

/** Actual generation state for one checked input; never a host assertion or a global flag. */
public record GenerationReadiness(RecipeRef recipe, boolean draftSupported, boolean generatorImplemented,
                                  boolean representable, List<GenerationDiagnostic> diagnostics) {
    public GenerationReadiness {
        Objects.requireNonNull(recipe);
        diagnostics = List.copyOf(diagnostics);
    }

    public boolean ready() {
        return draftSupported && generatorImplemented && representable && diagnostics.isEmpty();
    }

    /** Use this overload when a prior check may have outlived an edit or refreshed context. */
    public static GenerationReadiness inspect(CheckedStructure checked, org.ipro.design.EntityDraft currentDraft,
                                              org.ipro.design.ProjectDesignContext currentContext) {
        Objects.requireNonNull(currentDraft);
        Objects.requireNonNull(currentContext);
        if (checked.appliesTo(currentDraft, currentContext)) return inspect(checked);
        var descriptor = RecipeCatalog.descriptor(checked.source().recipe()).orElse(null);
        var code = GenerationDiagnostic.Code.STALE_CHECKED_STRUCTURE;
        return new GenerationReadiness(checked.source().recipe(), descriptor != null && descriptor.draftSupported(),
            descriptor != null && descriptor.generatorImplemented(), false,
            List.of(new GenerationDiagnostic(code,
                new DraftProblem.Target(currentDraft.draftId(), null, null, "checkedStructure"),
                "entityGeneration." + code.name(), Map.of())));
    }

    /** Checks the exact input a generator would receive; callers do not need a second validator. */
    public static GenerationReadiness inspect(CheckedStructure checked) {
        Objects.requireNonNull(checked, "checked");
        var recipe = checked.source().recipe();
        var descriptor = RecipeCatalog.descriptor(recipe).orElse(null);
        boolean draftSupported = descriptor != null && descriptor.draftSupported();
        boolean generatorImplemented = descriptor != null && descriptor.generatorImplemented();
        List<GenerationDiagnostic> diagnostics;
        if (RecipeCatalog.isRegisterRecipe(recipe)) {
            diagnostics = RegisterRepresentability.check(checked);
        } else if (generatorImplemented) {
            diagnostics = new StandardRepresentability(true,
                descriptor.generationProfile().orElseThrow().fingerprintProfile()).check(checked);
        } else {
            var code = GenerationDiagnostic.Code.UNSUPPORTED_RECIPE;
            diagnostics = List.of(new GenerationDiagnostic(code,
                    new DraftProblem.Target(checked.source().draftId(), null, null, "recipe"),
                    "entityGeneration." + code.name(),
                    Map.of("recipe", recipe.id() + "@" + recipe.version())));
        }
        return new GenerationReadiness(recipe, draftSupported, generatorImplemented,
                diagnostics.isEmpty(), diagnostics);
    }
}
