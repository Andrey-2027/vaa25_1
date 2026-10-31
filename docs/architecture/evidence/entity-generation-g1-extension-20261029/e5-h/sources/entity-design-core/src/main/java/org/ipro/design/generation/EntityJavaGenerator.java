package org.ipro.design.generation;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.ipro.design.CheckedStructure;
import org.ipro.design.DraftProblem;
import org.ipro.design.EntityDraft;
import org.ipro.design.ProjectDesignContext;
import org.ipro.design.RecipeCatalog;

/** Common boundary for the two exact supported generation recipes. */
public final class EntityJavaGenerator {
    public static final String CATALOG_GENERATOR_VERSION = "catalog-java-v4";
    public static final String DOCUMENT_GENERATOR_VERSION = "document-java-v4";
    public static String generatorVersion(org.ipro.design.RecipeRef recipe) {
        if (RecipeCatalog.STANDARD_CATALOG_V2.equals(recipe)) return CATALOG_GENERATOR_VERSION;
        if (RecipeCatalog.STANDARD_DOCUMENT.equals(recipe)) return DOCUMENT_GENERATOR_VERSION;
        throw new IllegalArgumentException("Unsupported generation recipe: " + recipe);
    }
    public static String fingerprintProfile(org.ipro.design.RecipeRef recipe) {
        generatorVersion(recipe);
        return GenerationManifest.EXTENSION_FINGERPRINT;
    }
    public GenerationResult generate(CheckedStructure checked, EntityDraft currentDraft,
                                     ProjectDesignContext currentContext) {
        Objects.requireNonNull(checked, "checked");
        Objects.requireNonNull(currentDraft, "currentDraft");
        Objects.requireNonNull(currentContext, "currentContext");
        if (RecipeCatalog.STANDARD_CATALOG_V2.equals(checked.source().recipe()))
            return new StandardRootJavaGenerator(RecipeCatalog.STANDARD_CATALOG_V2, CATALOG_GENERATOR_VERSION,
                    GenerationManifest.EXTENSION_FINGERPRINT, true).generate(checked, currentDraft, currentContext);
        if (RecipeCatalog.STANDARD_DOCUMENT.equals(checked.source().recipe()))
            return new StandardRootJavaGenerator(RecipeCatalog.STANDARD_DOCUMENT, DOCUMENT_GENERATOR_VERSION,
                    GenerationManifest.EXTENSION_FINGERPRINT, true).generate(checked, currentDraft, currentContext);
        var code = GenerationDiagnostic.Code.UNSUPPORTED_RECIPE;
        var recipe = checked.source().recipe();
        return GenerationResult.rejected(List.of(new GenerationDiagnostic(code,
                new DraftProblem.Target(checked.source().draftId(), null, null, "recipe"),
                "entityGeneration." + code.name(),
                Map.of("recipe", recipe.id() + "@" + recipe.version()))));
    }
}
