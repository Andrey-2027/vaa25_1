package org.ipro.design.source;

import java.nio.file.*;
import java.util.*;
import org.ipro.design.*;
import org.ipro.design.generation.EntityJavaGenerator;
import org.ipro.design.FieldApplicationMapping.CompositionMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** AF4.1 -> AF4.2: exact generated sources, without fixtures rewritten by the reader. */
class GeneratedFormColumnsReaderTest {
    @TempDir Path root;
    @Test void newCatalogDocumentAndBothRegisterRecipesRoundTripTheirExplicitComposition() throws Exception {
        var available = new RuntimeDesignSnapshot.Prerequisite(RuntimeDesignSnapshot.Status.AVAILABLE, "fixture");
        var prerequisites = new HashMap<String, RuntimeDesignSnapshot.Prerequisite>();
        for (var key : List.of("STANDARD_CATALOG_APP_API", "STANDARD_DOCUMENT_APP_API", "ET0_ET7_RUNTIME", "REGISTER_FACTS", "ET6_ET7_REGISTER_CLASSIFICATION", "REGISTER_ENGINE_OPERATIONS", "RLS_NUMERIC_REFERENCE_FILTERS")) prerequisites.put(key, available);
        var names = ProjectDesignContext.Names.known(Set.of());
        var target = new ReferenceTargetDescriptor("p.Ref", "Target", "STANDARD_ROOT", "java.lang.Long", ReferenceTargetDescriptor.Fact.YES,
            ReferenceTargetDescriptor.Fact.YES, ReferenceTargetDescriptor.Eligibility.SUPPORTED, "fixture");
        var context = new ProjectDesignContext("af42", "1", "p", names, names, names, ProjectDesignContext.Names.known(Set.of("FILE")),
            Map.of("p.Ref", target), true, new RuntimeDesignSnapshot("af42", true, Map.of(), prerequisites));
        for (var recipe : List.of(RecipeCatalog.STANDARD_CATALOG_V3, RecipeCatalog.STANDARD_DOCUMENT_V2,
                RecipeCatalog.INDEPENDENT_REGISTER_V3, RecipeCatalog.INDEPENDENT_REGISTER_RLS_V4)) {
            var dimensionId = new UUID(42, 1); var resourceId = new UUID(42, 2);
            boolean register = RecipeCatalog.isRegisterRecipe(recipe); boolean rls = RecipeCatalog.isRlsRegisterRecipe(recipe);
            var dimension = new FieldDraft(dimensionId, "scope", "Scope", rls ? "REFERENCE" : "STRING", DraftText.auto(), register,
                FieldDraft.UiRequired.AUTO, rls ? ConstraintDraft.empty() : new ConstraintDraft("80", "", ""),
                rls ? new ReferenceDraft("p.Ref") : null, FieldApplications.root());
            var resource = new FieldDraft(resourceId, "amount", "Amount", "DECIMAL", DraftText.auto(), false, FieldDraft.UiRequired.AUTO,
                new ConstraintDraft("", "15", "2"), null, FieldApplications.root());
            var reg = register ? new RegisterDraft(rls ? RegisterDraft.NONE : RegisterDraft.DAY, RegisterDraft.NAMED_AND_CANONICAL_CRUD,
                List.of(dimensionId), List.of(resourceId)) : null;
            if (rls) reg = reg.withRls(new RegisterRlsDraft("AF42_SCOPE", dimensionId));
            var draft = new EntityDraft(rls ? 4 : register ? 3 : 1, recipe, new UUID(42, 0),
                new EntityProperties("Generated", DraftText.auto(), "Generated", EntityProperties.Titles.auto(), DraftText.auto(), null, "FILE"),
                List.of(dimension, resource), List.of(), null, reg);
            var validation = new DraftValidator().validate(draft, context);
            assertFalse(validation.hasErrors(), validation.problems().toString());
            var checked = validation.checkedStructure().orElseThrow();
            var plan = new EntityJavaGenerator().generate(checked, draft, context).plan().orElseThrow();
            Path sources = root.resolve(recipe.id()); Files.createDirectories(sources.resolve("p"));
            for (var file : plan.files()) {
                Path path = sources.resolve(file.relativePath()); Files.createDirectories(path.getParent()); Files.writeString(path, file.content());
            }
            Files.writeString(sources.resolve("p/Ref.java"), "package p; @jakarta.persistence.Entity public class Ref extends org.ipro.crud.StandardCatalogEntity {}");
            var project = ProjectSources.scan(List.of(sources));
            var read = JavaEntityReader.read("p.Generated", plan.files().getFirst().content(), project);
            var projection = checked.applicationProjection();
            assertEquals(projection.listColumns(), read.listColumns().resolvedPaths(), recipe.toString());
            assertEquals(projection.selectColumns(), read.selectionColumns().resolvedPaths(), recipe.toString());
            assertEquals(CompositionMode.EXPLICIT, read.listColumns().mode());
            assertEquals(CompositionMode.EXPLICIT, read.selectionColumns().mode());
            assertEquals(plan.files().getLast().sha256(), read.listColumns().sourceSha256());
            assertEquals(List.of("scope", "amount"), read.draft().fields().stream().map(FieldDraft::name).toList());
            assertTrue(read.lockedFields().isEmpty(), read.lockedFields().toString());
        }
    }
}
