package org.ipro.design.generation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.ipro.design.CheckedStructure;
import org.ipro.design.DraftProblem;
import org.ipro.design.EntityDraft;
import org.ipro.design.FieldApplicationMapping;
import org.ipro.design.ProjectDesignContext;
import org.ipro.design.RecipeCatalog;
import org.ipro.design.RecipeRef;

/**
 * Shared root builder for explicitly selected standard recipes. It never writes
 * files, loads classes or re-validates the draft; the checked structure remains the only source
 * of truth and staleness is rejected.
 */
final class StandardRootJavaGenerator {
    private final RecipeRef recipe;
    private final String generatorVersion;
    private final String fingerprintProfile;
    private final boolean references;

    StandardRootJavaGenerator(RecipeRef recipe, String generatorVersion, String fingerprintProfile) {
        this(recipe, generatorVersion, fingerprintProfile, false);
    }
    StandardRootJavaGenerator(RecipeRef recipe, String generatorVersion, String fingerprintProfile, boolean references) {
        this.recipe = recipe;
        this.generatorVersion = generatorVersion;
        this.fingerprintProfile = fingerprintProfile;
        this.references = references;
    }

    public GenerationResult generate(CheckedStructure checked, EntityDraft currentDraft,
                                     ProjectDesignContext currentContext) {
        Objects.requireNonNull(checked, "checked");
        Objects.requireNonNull(currentDraft, "currentDraft");
        Objects.requireNonNull(currentContext, "currentContext");
        var source = checked.source();
        if (!recipe.equals(source.recipe()))
            return GenerationResult.rejected(List.of(new GenerationDiagnostic(
                    GenerationDiagnostic.Code.UNSUPPORTED_RECIPE,
                    target(source.draftId(), null, null, "recipe"),
                    messageKey(GenerationDiagnostic.Code.UNSUPPORTED_RECIPE),
                    Map.of("recipe", source.recipe().id() + "@" + source.recipe().version()))));
        if (!checked.appliesTo(currentDraft, currentContext))
            return GenerationResult.rejected(List.of(new GenerationDiagnostic(
                    GenerationDiagnostic.Code.STALE_CHECKED_STRUCTURE,
                    target(currentDraft.draftId(), null, null, "checkedStructure"),
                    messageKey(GenerationDiagnostic.Code.STALE_CHECKED_STRUCTURE),
                    Map.of())));
        var diagnostics = new StandardRepresentability(references, fingerprintProfile).check(checked);
        if (!diagnostics.isEmpty()) return GenerationResult.rejected(diagnostics);
        FieldApplicationMapping.Projection projection;
        try {
            projection = checked.applicationProjection();
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return GenerationResult.rejected(List.of(new GenerationDiagnostic(
                    GenerationDiagnostic.Code.PROJECTION_UNREPRESENTABLE,
                    target(source.draftId(), null, null, "applications"),
                    messageKey(GenerationDiagnostic.Code.PROJECTION_UNREPRESENTABLE),
                    Map.of())));
        }
        String content;
        try {
            content = JavaSourceBuilder.build(checked, projection);
        } catch (IllegalStateException | IllegalArgumentException ex) {
            return GenerationResult.rejected(List.of(new GenerationDiagnostic(
                    GenerationDiagnostic.Code.PROJECTION_UNREPRESENTABLE,
                    target(source.draftId(), null, null, ex.getMessage() != null && ex.getMessage().startsWith("OWNER_") ? "owner" : "applications"),
                    messageKey(GenerationDiagnostic.Code.PROJECTION_UNREPRESENTABLE), Map.of())));
        }
        var file = new GenerationFile(GenerationFile.Operation.CREATE, relativePath(checked),
                content, GenerationHash.sha256(content));
        var files = new ArrayList<GenerationFile>(); files.add(file);
        for (int index = 0; index < checked.sections().size(); index++) {
            var section = checked.sections().get(index);
            try {
                var row = JavaSourceBuilder.buildRow(checked, section, projection.sections().get(index), index);
                files.add(new GenerationFile(GenerationFile.Operation.CREATE,
                    section.packageName().replace('.', '/') + "/" + section.className() + ".java", row, GenerationHash.sha256(row)));
            } catch (IllegalArgumentException | IllegalStateException ex) {
                return GenerationResult.rejected(List.of(new GenerationDiagnostic(GenerationDiagnostic.Code.PROJECTION_UNREPRESENTABLE,
                    target(source.draftId(), section.id(), null, "applications"), messageKey(GenerationDiagnostic.Code.PROJECTION_UNREPRESENTABLE), Map.of())));
            }
        }
        if (RecipeCatalog.formConfigurationRecipe(source.recipe()))
            files.add(FormConfigurationSourceBuilder.file(checked, projection));
        var planId = PlanFingerprint.planId(fingerprintProfile, source.recipe(), generatorVersion, currentDraft, currentContext, files, checked.owner(), projection.owner());
        return GenerationResult.generated(new GenerationPlan(planId, source.recipe(), generatorVersion,
                currentDraft, currentContext, files));
    }

    private static String relativePath(CheckedStructure checked) {
        return checked.root().packageName().replace('.', '/') + "/" + checked.root().className() + ".java";
    }

    private static String messageKey(GenerationDiagnostic.Code code) {
        return "entityGeneration." + code.name();
    }

    private static DraftProblem.Target target(UUID draftId, UUID sectionId, UUID fieldId, String propertyKey) {
        return new DraftProblem.Target(draftId, sectionId, fieldId, propertyKey);
    }
}
