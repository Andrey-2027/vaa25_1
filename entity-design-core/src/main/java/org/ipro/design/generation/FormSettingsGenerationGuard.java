package org.ipro.design.generation;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.ipro.design.DraftProblem;
import org.ipro.design.RecipeCatalog;
import org.ipro.design.RecipeRef;

/** Final check of controlled generator templates; quoted labels and comments are not declarations. */
final class FormSettingsGenerationGuard {
    private static final Pattern TEXT = Pattern.compile("/\\*.*?\\*/|//[^\\r\\n]*|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'", Pattern.DOTALL);
    private static final Pattern LEGACY = Pattern.compile("@(?:[\\w$]+\\.)*GridColumn\\b|\\b(?:listColumns|selectColumns|readOnly|grid)\\s*=|@(?:[\\w$]+\\.)*Lookup\\s*\\([^)]*\\b(?:entity|variant|fetch)\\s*=");
    private FormSettingsGenerationGuard() {}
    static List<GenerationDiagnostic> check(RecipeRef recipe, UUID draft, List<GenerationFile> files) {
        if (!RecipeCatalog.strictFormSettingsRecipe(recipe)) return List.of();
        var failures = new java.util.ArrayList<GenerationDiagnostic>();
        for (var file : files) {
            String code = TEXT.matcher(file.content()).replaceAll(" ");
            var match = LEGACY.matcher(code);
            if (match.find() || code.contains("@Entity\n") && !code.contains("@FormSettingsOnly"))
                failures.add(new GenerationDiagnostic(GenerationDiagnostic.Code.FORBIDDEN_FORM_SETTING,
                    new DraftProblem.Target(draft, null, null, file.relativePath()), "entityGeneration.FORBIDDEN_FORM_SETTING",
                    Map.of("file", file.relativePath(), "reason", "New recipes require form settings outside entity annotations")));
        }
        return List.copyOf(failures);
    }
}
