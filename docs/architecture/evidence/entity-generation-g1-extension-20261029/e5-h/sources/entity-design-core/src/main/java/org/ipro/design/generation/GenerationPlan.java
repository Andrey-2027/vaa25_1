package org.ipro.design.generation;

import java.util.List;
import java.util.Objects;
import org.ipro.design.EntityDraft;
import org.ipro.design.ProjectDesignContext;
import org.ipro.design.RecipeRef;

/** Immutable reviewable CREATE plan bound to the exact checked draft and context. No IO or callbacks. */
public record GenerationPlan(String planId, RecipeRef recipe, String generatorVersion,
                             EntityDraft source, ProjectDesignContext context, List<GenerationFile> files) {
    private static final java.util.regex.Pattern PLAN_ID = java.util.regex.Pattern.compile("g1-[0-9a-f]{64}");

    public GenerationPlan {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(recipe, "recipe");
        Objects.requireNonNull(generatorVersion, "generatorVersion");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(context, "context");
        files = List.copyOf(files);
        if (!PLAN_ID.matcher(planId).matches())
            throw new IllegalArgumentException("planId must be g1- plus 64 lowercase hex characters");
        if (!recipe.equals(source.recipe()))
            throw new IllegalArgumentException("Plan recipe must match the source draft recipe");
        if (generatorVersion.isBlank())
            throw new IllegalArgumentException("generatorVersion is required");
        if (files.isEmpty())
            throw new IllegalArgumentException("A plan must contain at least one file entry");
    }

    /** A stale plan must be rebuilt, not accepted for another draft/context pair. */
    public boolean appliesTo(EntityDraft draft, ProjectDesignContext current) {
        return source.equals(draft) && context.equals(current);
    }
}
