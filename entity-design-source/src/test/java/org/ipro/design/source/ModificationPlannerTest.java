package org.ipro.design.source;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.ipro.design.ConstraintDraft;
import org.ipro.design.DraftProblem;
import org.ipro.design.DraftText;
import org.ipro.design.EntityDraft;
import org.ipro.design.FieldApplications;
import org.ipro.design.FieldDraft;
import org.ipro.design.ReferenceDraft;
import org.ipro.design.existing.EntityChange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ModificationPlannerTest {
    static final String WORKSHOP = """
        package t;

        import jakarta.persistence.Column;
        import jakarta.persistence.Entity;
        import jakarta.persistence.Table;
        import org.ipro.crud.StandardCatalogEntity;
        import org.ipro.metadata.annotation.FieldMetadata;

        @Entity
        @Table(name = "workshops")
        public class Workshop extends StandardCatalogEntity {

            @Column(name = "note", length = 100)
            @FieldMetadata(label = "Примечание", order = 30)
            private String note;

            public String getNote() {
                return note;
            }

            public void setNote(String note) {
                this.note = note;
            }
        }
        """;
    static final String UNIT = """
        package t;

        import jakarta.persistence.Entity;
        import org.ipro.crud.StandardCatalogEntity;

        @Entity
        public class Unit extends StandardCatalogEntity {
        }
        """;
    static final String SUB = """
        package t;

        import jakarta.persistence.Entity;

        @Entity
        public class Sub extends Workshop {
        }
        """;

    static void write(Path root, String name, String text) throws IOException {
        Files.createDirectories(root.resolve("t"));
        Files.writeString(root.resolve("t").resolve(name + ".java"), text, StandardCharsets.UTF_8);
    }

    static FieldDraft field(String name, String type, ReferenceDraft reference) {
        return new FieldDraft(ModificationPlanner.newFieldId("t.Workshop", name), name, "Реквизит " + name, type,
            DraftText.auto(), false, FieldDraft.UiRequired.AUTO,
            type.equals("STRING") ? new ConstraintDraft("200", "", "") : ConstraintDraft.empty(), reference, FieldApplications.root());
    }

    static UnaryOperator<EntityDraft> add(FieldDraft... fields) {
        return draft -> { var all = new ArrayList<>(draft.fields()); all.addAll(List.of(fields)); return draft.withFields(all); };
    }

    private static ModificationPlanner.Outcome plan(Path root, UnaryOperator<EntityDraft> edit) {
        return ModificationPlanner.plan(root, "t.Workshop", edit, Stubs.classpath());
    }

    private static Path fixture(Path root) throws IOException {
        write(root, "Workshop", WORKSHOP);
        write(root, "Unit", UNIT);
        return root;
    }

    private static long scratchDirectories() throws IOException {
        try (Stream<Path> list = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return list.filter(path -> path.getFileName().toString().startsWith("entity-modify-") && !path.getFileName().toString().startsWith("entity-modify-stubs-")).count();
        }
    }

    @Test void aNewFieldGivesAPlanWithTheTextTheSqlAndTheBytesItWasBuiltFrom(@TempDir Path root) throws IOException {
        fixture(root);
        var outcome = plan(root, add(field("address", "STRING", null)));
        assertTrue(outcome.succeeded(), String.valueOf(outcome.refusal()) + outcome.problems());
        var plan = outcome.plan();
        assertEquals("t/Workshop.java", plan.relativePath());
        assertEquals(WORKSHOP, plan.baseContent());
        assertTrue(plan.newContent().contains("private String address;"));
        assertTrue(plan.newContent().contains("public String getAddress()"));
        assertEquals(1, plan.changes().size());
        assertEquals(List.of("ALTER TABLE workshops ADD COLUMN address VARCHAR(200);"), plan.schemaStatements());
        assertEquals(Map.of(), plan.dependencyHashes());
        assertEquals(ModificationPlanner.TOOL_VERSION, plan.toolVersion());
        assertEquals(WORKSHOP, Files.readString(root.resolve("t/Workshop.java")), "planning writes nothing");
    }

    @Test void theSameInputGivesTheSamePlan(@TempDir Path root) throws IOException {
        fixture(root);
        assertEquals(plan(root, add(field("address", "STRING", null))).plan().planId(), plan(root, add(field("address", "STRING", null))).plan().planId());
        assertNotEquals(plan(root, add(field("address", "STRING", null))).plan().planId(), plan(root, add(field("zip", "STRING", null))).plan().planId());
    }

    @Test void theBaseClassesTheSubclassesAndTheTargetOfAReferenceAreDependencies(@TempDir Path root) throws IOException {
        fixture(root);
        write(root, "Sub", SUB);
        var outcome = plan(root, add(field("unit", "REFERENCE", new ReferenceDraft("t.Unit"))));
        assertTrue(outcome.succeeded(), String.valueOf(outcome.refusal()) + outcome.problems());
        var plan = outcome.plan();
        assertEquals(List.of("t/Sub.java", "t/Unit.java"), List.copyOf(plan.dependencyHashes().keySet()));
        assertEquals(JavaSources.sha256(SUB.getBytes(StandardCharsets.UTF_8)), plan.dependencyHashes().get("t/Sub.java"));
        assertEquals(JavaSources.sha256(UNIT.getBytes(StandardCharsets.UTF_8)), plan.dependencyHashes().get("t/Unit.java"));
        assertEquals(List.of("ALTER TABLE workshops ADD COLUMN unit_id BIGINT;",
            "ALTER TABLE workshops ADD CONSTRAINT fk_workshops_unit_id FOREIGN KEY (unit_id) REFERENCES unit (id);"), plan.schemaStatements());
    }

    @Test void aProjectBaseOfTheEntityIsADependency(@TempDir Path root) throws IOException {
        fixture(root);
        write(root, "Workshop", WORKSHOP.replace("extends StandardCatalogEntity", "extends Common"));
        write(root, "Common", "package t;\n\nimport jakarta.persistence.MappedSuperclass;\nimport org.ipro.crud.StandardCatalogEntity;\n\n@MappedSuperclass\npublic abstract class Common extends StandardCatalogEntity {\n}\n");
        var outcome = plan(root, add(field("address", "STRING", null)));
        assertTrue(outcome.succeeded(), String.valueOf(outcome.refusal()) + outcome.problems());
        assertEquals(List.of("t/Common.java"), List.copyOf(outcome.plan().dependencyHashes().keySet()));
    }

    @Test void aNewFieldAndANewLabelTogetherAreOnePlan(@TempDir Path root) throws IOException {
        fixture(root);
        UnaryOperator<EntityDraft> both = draft -> add(field("address", "STRING", null)).apply(draft.withFields(
            draft.fields().stream().map(f -> new FieldDraft(f.id(), f.name(), "Заметка", f.type(), f.columnName(), f.serverRequired(), f.uiRequired(),
                f.constraints(), f.reference(), f.applications())).toList()));
        var outcome = plan(root, both);
        assertTrue(outcome.succeeded(), String.valueOf(outcome.refusal()) + outcome.problems());
        var plan = outcome.plan();
        assertEquals(2, plan.changes().size());
        assertInstanceOf(EntityChange.LabelChanged.class, plan.changes().get(0));
        assertTrue(plan.newContent().contains("label = \"Заметка\""));
        assertFalse(plan.newContent().contains("label = \"Примечание\""));
        assertTrue(plan.newContent().contains("private String address;"));
        assertEquals(1, plan.schemaStatements().size());
    }

    @Test void aNewLabelAloneNeedsNoSql(@TempDir Path root) throws IOException {
        fixture(root);
        var outcome = plan(root, draft -> draft.withFields(draft.fields().stream().map(f -> new FieldDraft(f.id(), f.name(), "Заметка", f.type(),
            f.columnName(), f.serverRequired(), f.uiRequired(), f.constraints(), f.reference(), f.applications())).toList()));
        assertTrue(outcome.succeeded(), String.valueOf(outcome.refusal()) + outcome.problems());
        assertEquals(List.of(), outcome.plan().schemaStatements());
        assertEquals(WORKSHOP.replace("label = \"Примечание\"", "label = \"Заметка\""), outcome.plan().newContent());
    }

    @Test void anEditWithoutADifferenceIsNothingToDo(@TempDir Path root) throws IOException {
        fixture(root);
        var outcome = plan(root, UnaryOperator.identity());
        assertFalse(outcome.succeeded());
        assertEquals(SourceRefusal.Code.NOTHING_TO_DO, outcome.refusal().code());
    }

    @Test void problemsOfTheEditedDraftAreReportedAndNothingIsPlanned(@TempDir Path root) throws IOException {
        fixture(root);
        var duplicate = plan(root, add(field("note", "STRING", null)));
        assertNull(duplicate.plan());
        assertNull(duplicate.refusal());
        assertTrue(duplicate.problems().stream().anyMatch(p -> p.code() == DraftProblem.Code.RESERVED_FIELD && p.target().propertyKey().equals("name")), duplicate.problems().toString());

        var removed = plan(root, draft -> draft.withFields(List.of()));
        assertTrue(removed.problems().stream().anyMatch(p -> p.code() == DraftProblem.Code.MODIFICATION_UNSUPPORTED), removed.problems().toString());

        var required = plan(root, draft -> draft.withFields(add(new FieldDraft(new UUID(1, 1), "must", "Обязательное", "STRING", DraftText.auto(), true,
            FieldDraft.UiRequired.AUTO, new ConstraintDraft("10", "", ""), null, FieldApplications.root())).apply(draft).fields()));
        assertTrue(required.problems().stream().anyMatch(p -> p.code() == DraftProblem.Code.MODIFICATION_UNSUPPORTED), required.problems().toString());
        assertEquals(WORKSHOP, Files.readString(root.resolve("t/Workshop.java")));
    }

    @Test void aClassPathIsRequired(@TempDir Path root) throws IOException {
        fixture(root);
        var outcome = ModificationPlanner.plan(root, "t.Workshop", add(field("address", "STRING", null)), List.of());
        assertEquals(SourceRefusal.Code.CLASSPATH_MISSING, outcome.refusal().code());
    }

    @Test void aPatchedFileThatDoesNotCompileIsRefusedWithTheDiagnosticAndLeavesNoScratchFiles(@TempDir Path root, @TempDir Path empty) throws IOException {
        fixture(root);
        long before = scratchDirectories();
        var outcome = ModificationPlanner.plan(root, "t.Workshop", add(field("address", "STRING", null)), List.of(empty));
        assertEquals(SourceRefusal.Code.COMPILE_FAILED, outcome.refusal().code());
        assertTrue(outcome.refusal().reason().contains("Workshop.java:"), outcome.refusal().reason());
        assertEquals(before, scratchDirectories());
        assertEquals(WORKSHOP, Files.readString(root.resolve("t/Workshop.java")));
    }

    @Test void whenTheTableIsUnknownThereIsNoPlan(@TempDir Path root) throws IOException {
        fixture(root);
        write(root, "Workshop", WORKSHOP.replace("@Table(name = \"workshops\")", "@Table(name = \"workshops\", schema = \"s\")"));
        var outcome = plan(root, add(field("address", "STRING", null)));
        assertEquals(SourceRefusal.Code.SCHEMA_UNKNOWN, outcome.refusal().code());
        assertNull(outcome.plan());
    }

    @Test void aTypeThatIsNotInTheSourcesIsRefused(@TempDir Path root) throws IOException {
        fixture(root);
        var outcome = ModificationPlanner.plan(root, "t.Missing", UnaryOperator.identity(), Stubs.classpath());
        assertEquals(SourceRefusal.Code.NOT_AN_ENTITY_CLASS, outcome.refusal().code());
    }

    @Test void aFileThatCannotBeParsedElsewhereIsAWarning(@TempDir Path root) throws IOException {
        fixture(root);
        write(root, "Broken", "package t; public class Broken { int ; }");
        var outcome = plan(root, add(field("address", "STRING", null)));
        assertTrue(outcome.succeeded(), String.valueOf(outcome.refusal()) + outcome.problems());
        assertTrue(outcome.plan().warnings().stream().anyMatch(w -> w.code() == DraftProblem.Code.PROJECT_FACT_UNKNOWN
            && w.severity() == DraftProblem.Severity.WARNING && w.parameters().get("value").contains("t/Broken.java")), outcome.plan().warnings().toString());
    }
}
