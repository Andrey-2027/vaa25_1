package org.ipro.design.generation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.ipro.design.CheckedStructure;
import org.ipro.design.DraftProblem;
import org.ipro.design.EntityDraft;
import org.ipro.design.FieldApplicationMapping;
import org.ipro.design.ProjectDesignContext;
import org.ipro.design.RecipeCatalog;

/** Generator for the independent-register profile; it never writes files. */
final class IndependentRegisterJavaGenerator {
    private static final org.ipro.design.RecipeGenerationProfile PROFILE = RecipeCatalog
        .descriptor(RecipeCatalog.INDEPENDENT_REGISTER).orElseThrow().generationProfile().orElseThrow();
    static final String VERSION = PROFILE.generatorVersion();
    static final String FINGERPRINT = PROFILE.fingerprintProfile();

    public GenerationResult generate(CheckedStructure checked, EntityDraft currentDraft,
                                     ProjectDesignContext currentContext) {
        Objects.requireNonNull(checked, "checked");
        Objects.requireNonNull(currentDraft, "currentDraft");
        Objects.requireNonNull(currentContext, "currentContext");
        var source = checked.source();
        if (!RecipeCatalog.isRegisterRecipe(source.recipe()))
            return GenerationResult.rejected(List.of(new GenerationDiagnostic(
                    GenerationDiagnostic.Code.UNSUPPORTED_RECIPE,
                    target(source.draftId(), "recipe"),
                    messageKey(GenerationDiagnostic.Code.UNSUPPORTED_RECIPE),
                    Map.of("recipe", source.recipe().id() + "@" + source.recipe().version()))));
        if (!checked.appliesTo(currentDraft, currentContext))
            return GenerationResult.rejected(List.of(new GenerationDiagnostic(
                    GenerationDiagnostic.Code.STALE_CHECKED_STRUCTURE,
                    target(currentDraft.draftId(), "checkedStructure"),
                    messageKey(GenerationDiagnostic.Code.STALE_CHECKED_STRUCTURE), Map.of())));
        var diagnostics = RegisterRepresentability.check(checked);
        if (!diagnostics.isEmpty()) return GenerationResult.rejected(diagnostics);

        FieldApplicationMapping.Projection projection;
        try {
            projection = checked.applicationProjection();
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return GenerationResult.rejected(List.of(projectionFailure(source.draftId(), "applications")));
        }
        String content;
        try {
            content = RegisterJavaSourceBuilder.build(checked, projection);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return GenerationResult.rejected(List.of(projectionFailure(source.draftId(), "applications")));
        }
        var relativePath = checked.root().packageName().replace('.', '/') + "/"
                + checked.root().className() + ".java";
        var file = new GenerationFile(GenerationFile.Operation.CREATE, relativePath, content,
                GenerationHash.sha256(content));
        var files = new ArrayList<GenerationFile>();
        files.add(file);
        if (RecipeCatalog.formConfigurationRecipe(source.recipe()))
            files.add(FormConfigurationSourceBuilder.file(checked, projection));
        var profile = RecipeCatalog.descriptor(source.recipe()).orElseThrow().generationProfile().orElseThrow();
        var planId = PlanFingerprint.planId(profile.fingerprintProfile(), source.recipe(), profile.generatorVersion(),
                currentDraft, currentContext, files);
        return GenerationResult.generated(new GenerationPlan(planId, source.recipe(), profile.generatorVersion(),
                currentDraft, currentContext, files));
    }

    private static GenerationDiagnostic projectionFailure(java.util.UUID draftId, String property) {
        return new GenerationDiagnostic(GenerationDiagnostic.Code.PROJECTION_UNREPRESENTABLE,
                target(draftId, property),
                messageKey(GenerationDiagnostic.Code.PROJECTION_UNREPRESENTABLE), Map.of());
    }

    private static String messageKey(GenerationDiagnostic.Code code) {
        return "entityGeneration." + code.name();
    }

    private static DraftProblem.Target target(java.util.UUID draftId, String property) {
        return new DraftProblem.Target(draftId, null, null, property);
    }
}
