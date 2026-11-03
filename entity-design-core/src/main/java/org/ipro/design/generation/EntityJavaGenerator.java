package org.ipro.design.generation;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.ipro.design.CheckedStructure;
import org.ipro.design.DraftProblem;
import org.ipro.design.EntityDraft;
import org.ipro.design.ProjectDesignContext;
import org.ipro.design.RecipeCatalog;

/** Common boundary for exact, described generation recipes. */
public final class EntityJavaGenerator {
    public static final String CATALOG_GENERATOR_VERSION = "catalog-java-v4";
    public static final String DOCUMENT_GENERATOR_VERSION = "document-java-v4";
    public static final String REGISTER_GENERATOR_VERSION = "register-java-v1";
    public static String generatorVersion(org.ipro.design.RecipeRef recipe) {
        return profile(recipe).generatorVersion();
    }
    public static String fingerprintProfile(org.ipro.design.RecipeRef recipe) {
        return profile(recipe).fingerprintProfile();
    }
    private static org.ipro.design.RecipeGenerationProfile profile(org.ipro.design.RecipeRef recipe) {
        return RecipeCatalog.descriptor(recipe).flatMap(org.ipro.design.RecipeDescriptor::generationProfile)
            .orElseThrow(() -> new IllegalArgumentException("Unsupported generation recipe: " + recipe));
    }
    /** Actual implementation state, independent of the legacy global generation flag. */
    public static boolean generationImplemented(org.ipro.design.RecipeRef recipe) {
        return RecipeCatalog.descriptor(recipe).map(descriptor -> descriptor.generatorImplemented()).orElse(false);
    }
    /** Early readiness for G1R.4; the generator applies the same representability gate. */
    public static GenerationReadiness readiness(CheckedStructure checked) {
        return GenerationReadiness.inspect(checked);
    }
    public static GenerationReadiness readiness(CheckedStructure checked, EntityDraft currentDraft,
                                                 ProjectDesignContext currentContext) {
        return GenerationReadiness.inspect(checked, currentDraft, currentContext);
    }
    public GenerationResult generate(CheckedStructure checked, EntityDraft currentDraft,
                                     ProjectDesignContext currentContext) {
        Objects.requireNonNull(checked, "checked");
        Objects.requireNonNull(currentDraft, "currentDraft");
        Objects.requireNonNull(currentContext, "currentContext");
        var selected = RecipeCatalog.descriptor(checked.source().recipe())
            .flatMap(org.ipro.design.RecipeDescriptor::generationProfile);
        if (selected.isPresent()) {
            var profile = selected.orElseThrow();
            return switch (profile.builder()) {
                case STANDARD_ROOT -> new StandardRootJavaGenerator(checked.source().recipe(),
                    profile.generatorVersion(), profile.fingerprintProfile(), true)
                    .generate(checked, currentDraft, currentContext);
                case INDEPENDENT_REGISTER -> new IndependentRegisterJavaGenerator()
                    .generate(checked, currentDraft, currentContext);
            };
        }
        var code = GenerationDiagnostic.Code.UNSUPPORTED_RECIPE;
        var recipe = checked.source().recipe();
        return GenerationResult.rejected(List.of(new GenerationDiagnostic(code,
                new DraftProblem.Target(checked.source().draftId(), null, null, "recipe"),
                "entityGeneration." + code.name(),
                Map.of("recipe", recipe.id() + "@" + recipe.version()))));
    }
}
