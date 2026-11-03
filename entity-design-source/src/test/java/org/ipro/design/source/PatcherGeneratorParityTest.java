package org.ipro.design.source;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.ipro.design.ConstraintDraft;
import org.ipro.design.DraftText;
import org.ipro.design.DraftValidator;
import org.ipro.design.EntityDraft;
import org.ipro.design.EntityProperties;
import org.ipro.design.FieldApplications;
import org.ipro.design.FieldDraft;
import org.ipro.design.ProjectDesignContext;
import org.ipro.design.RecipeCatalog;
import org.ipro.design.RecipeRef;
import org.ipro.design.ReferenceDraft;
import org.ipro.design.ReferenceTargetDescriptor;
import org.ipro.design.RuntimeDesignSnapshot;
import org.ipro.design.existing.EntityChange;
import org.ipro.design.generation.EntityJavaGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * K2 of the entity-modification plan: for a file the generator wrote, adding a field with the patcher
 * gives byte for byte the file the generator writes for the draft that already has that field.
 */
class PatcherGeneratorParityTest {
    private static final String PACKAGE = "com.example.parity";
    private static final String TARGET = "com.example.parity.targets.Unit";
    private static final List<String> TYPES = List.of("STRING", "INTEGER", "LONG", "DECIMAL", "BOOLEAN", "DATE", "DATETIME", "REFERENCE");

    private static ProjectDesignContext context() {
        var names = ProjectDesignContext.Names.known(Set.of());
        var target = new ReferenceTargetDescriptor(TARGET, "Единицы", "STANDARD_ROOT", "java.lang.Long", ReferenceTargetDescriptor.Fact.YES,
            ReferenceTargetDescriptor.Fact.YES, ReferenceTargetDescriptor.Eligibility.SUPPORTED, "fixture");
        var available = new RuntimeDesignSnapshot.Prerequisite(RuntimeDesignSnapshot.Status.AVAILABLE, "fixture");
        var snapshot = new RuntimeDesignSnapshot("parity", true, Map.of(), Map.of(
            "STANDARD_CATALOG_APP_API", available, "STANDARD_DOCUMENT_APP_API", available, "ET0_ET7_RUNTIME", available));
        return new ProjectDesignContext("parity", "v1", PACKAGE, names, names, names, ProjectDesignContext.Names.known(Set.of("FILE")),
            Map.of(TARGET, target), true, snapshot);
    }

    private static FieldDraft field(int index, String type, boolean form, boolean list, boolean selection) {
        String name = "field" + index;
        return new FieldDraft(new UUID(9, index), name, "Реквизит " + index, type, DraftText.auto(), false, FieldDraft.UiRequired.AUTO,
            switch (type) {
                case "STRING" -> new ConstraintDraft("120", "", "");
                case "DECIMAL" -> new ConstraintDraft("", "15", "4");
                default -> ConstraintDraft.empty();
            }, type.equals("REFERENCE") ? new ReferenceDraft(TARGET) : null, new FieldApplications(form, list, selection, false));
    }

    private static EntityDraft draft(RecipeRef recipe, String className, List<FieldDraft> fields) {
        var entity = new EntityProperties(className, DraftText.explicit(PACKAGE), "Образец", EntityProperties.Titles.auto(), DraftText.auto(), null, "FILE");
        return new EntityDraft(1, recipe, new UUID(9, 0), entity, fields, List.of());
    }

    private static String generate(EntityDraft draft) {
        var context = context();
        var result = new DraftValidator().validate(draft, context);
        assertFalse(result.hasErrors(), result.problems().toString());
        var generated = new EntityJavaGenerator().generate(result.checkedStructure().orElseThrow(), draft, context);
        assertTrue(generated.succeeded(), generated.diagnostics().toString());
        return generated.plan().orElseThrow().files().getFirst().content();
    }

    private static void assertParity(Path dir, RecipeRef recipe, String className, List<FieldDraft> existing, FieldDraft added) throws Exception {
        String before = generate(draft(recipe, className, existing));
        var all = new ArrayList<>(existing); all.add(added);
        String expected = generate(draft(recipe, className, all));

        Path file = dir.resolve(PACKAGE.replace('.', '/')).resolve(className + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, before);
        var project = ProjectSources.scan(List.of(dir));
        String typeId = PACKAGE + "." + className;
        var entity = JavaEntityReader.read(typeId, before, project);
        assertEquals(existing.size(), entity.draft().fields().size(), "generated fields are all readable");
        assertTrue(entity.lockedFields().isEmpty(), entity.lockedFields().toString());

        String patched = JavaEntityPatcher.patch(before, entity, List.of(new EntityChange.FieldAdded(added)), project).text();
        assertEquals(expected, patched, className + " + " + added.type() + " " + added.applications());
    }

    @Test void everyTypeIsAddedToACatalogAndADocumentLikeTheGeneratorWritesIt(@TempDir Path dir) throws Exception {
        for (var recipe : List.of(RecipeCatalog.STANDARD_CATALOG_V2, RecipeCatalog.STANDARD_DOCUMENT)) {
            String name = recipe.equals(RecipeCatalog.STANDARD_DOCUMENT) ? "ParityDocument" : "ParityCatalog";
            for (String type : TYPES) {
                // From an empty body, and after one and two generated fields.
                assertParity(dir, recipe, name, List.of(), field(1, type, true, true, true));
                assertParity(dir, recipe, name, List.of(field(1, "STRING", true, true, true)), field(2, type, true, true, true));
                assertParity(dir, recipe, name, List.of(field(1, "STRING", true, true, true), field(2, "DECIMAL", true, false, false)), field(3, type, true, true, true));
            }
        }
    }

    @Test void everyPublicationOfANewFieldMatchesTheGenerator(@TempDir Path dir) throws Exception {
        for (boolean form : List.of(true, false)) {
            for (boolean list : List.of(true, false)) {
                for (boolean selection : List.of(true, false)) {
                    assertParity(dir, RecipeCatalog.STANDARD_CATALOG_V2, "ParityCatalog", List.of(field(1, "STRING", true, true, true)), field(2, "STRING", form, list, selection));
                    assertParity(dir, RecipeCatalog.STANDARD_DOCUMENT, "ParityDocument", List.of(), field(1, "INTEGER", form, list, selection));
                }
            }
        }
    }

    @Test void aRequiredModeIsWrittenLikeTheGeneratorWritesIt(@TempDir Path dir) throws Exception {
        for (var mode : List.of(FieldDraft.UiRequired.REQUIRED, FieldDraft.UiRequired.OPTIONAL)) {
            var base = field(2, "STRING", true, true, true);
            var added = new FieldDraft(base.id(), base.name(), base.label(), base.type(), base.columnName(), false, mode, base.constraints(), null, base.applications());
            assertParity(dir, RecipeCatalog.STANDARD_CATALOG_V2, "ParityCatalog", List.of(field(1, "STRING", true, true, true)), added);
        }
    }

    @Test void anExplicitColumnAndAnAwkwardLabelAreWrittenLikeTheGeneratorWritesThem(@TempDir Path dir) throws Exception {
        var base = field(2, "STRING", true, true, true);
        var added = new FieldDraft(base.id(), "other", "Он сказал \"да\" \\ и\nушёл", "STRING", DraftText.explicit("other_col"), false,
            FieldDraft.UiRequired.AUTO, base.constraints(), null, base.applications());
        assertParity(dir, RecipeCatalog.STANDARD_CATALOG_V2, "ParityCatalog", List.of(field(1, "STRING", true, true, true)), added);
    }
}
