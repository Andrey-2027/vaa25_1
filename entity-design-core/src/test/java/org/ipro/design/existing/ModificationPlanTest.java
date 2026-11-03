package org.ipro.design.existing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.ipro.design.DraftProblem;
import org.ipro.design.generation.GenerationHash;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModificationPlanTest {
    private static final String BASE = "class A {\n}\n", NEW = "class A {\n    int x;\n}\n";

    private static DraftProblem warning(String value) {
        return new DraftProblem(DraftProblem.Severity.WARNING, DraftProblem.Code.PROJECT_FACT_UNKNOWN,
            new DraftProblem.Target(new UUID(1, 1), null, null, "context"), "entityDraft.PROJECT_FACT_UNKNOWN", Map.of("value", value));
    }

    private static ModificationPlan plan(String tool, String path, String base, String now, Map<String, String> dependencies,
                                         List<EntityChange> changes, List<String> sql, List<DraftProblem> warnings) {
        return ModificationPlan.create(tool, path, base, now, dependencies, changes, sql, warnings);
    }

    private static ModificationPlan plan() {
        return plan("tool-1", "a/A.java", BASE, NEW, Map.of("a/B.java", "11", "a/C.java", "22"),
            List.of(new EntityChange.FieldAdded(EntityChangesTest.added())), List.of("ALTER TABLE a ADD COLUMN x INTEGER;"), List.of(warning("w")));
    }

    @Test void theSameInputGivesTheSameIdentifier() {
        assertEquals(plan().planId(), plan().planId());
        assertTrue(plan().planId().matches("em1-[0-9a-f]{64}"));
    }

    @Test void theOrderOfTheDependencyMapDoesNotMatter() {
        var reversed = new LinkedHashMap<String, String>();
        reversed.put("a/C.java", "22"); reversed.put("a/B.java", "11");
        var other = plan("tool-1", "a/A.java", BASE, NEW, reversed, plan().changes(), plan().schemaStatements(), plan().warnings());
        assertEquals(plan().planId(), other.planId());
        assertEquals(List.copyOf(plan().dependencyHashes().keySet()), List.of("a/B.java", "a/C.java"));
    }

    @Test void everyPartOfThePlanChangesTheIdentifier() {
        String id = plan().planId();
        var deps = Map.of("a/B.java", "11", "a/C.java", "22");
        var changes = plan().changes();
        var sql = plan().schemaStatements();
        var warnings = plan().warnings();
        var added = EntityChangesTest.added();
        var otherField = new org.ipro.design.FieldDraft(added.id(), added.name(), "Другая подпись", added.type(), added.columnName(),
            added.serverRequired(), added.uiRequired(), added.constraints(), added.reference(), added.applications());
        var variants = List.of(
            plan("tool-2", "a/A.java", BASE, NEW, deps, changes, sql, warnings),
            plan("tool-1", "a/Z.java", BASE, NEW, deps, changes, sql, warnings),
            plan("tool-1", "a/A.java", "class A { }\n", NEW, deps, changes, sql, warnings),
            plan("tool-1", "a/A.java", BASE, "class A {\n    long x;\n}\n", deps, changes, sql, warnings),
            plan("tool-1", "a/A.java", BASE, NEW, Map.of("a/B.java", "11"), changes, sql, warnings),
            plan("tool-1", "a/A.java", BASE, NEW, Map.of("a/B.java", "11", "a/C.java", "23"), changes, sql, warnings),
            plan("tool-1", "a/A.java", BASE, NEW, deps, List.of(new EntityChange.FieldAdded(otherField)), sql, warnings),
            plan("tool-1", "a/A.java", BASE, NEW, deps, List.of(), sql, warnings),
            plan("tool-1", "a/A.java", BASE, NEW, deps, changes, List.of(), warnings),
            plan("tool-1", "a/A.java", BASE, NEW, deps, changes, List.of("ALTER TABLE a ADD COLUMN y INTEGER;"), warnings),
            plan("tool-1", "a/A.java", BASE, NEW, deps, changes, sql, List.of()),
            plan("tool-1", "a/A.java", BASE, NEW, deps, changes, sql, List.of(warning("other"))));
        var seen = new java.util.HashSet<String>();
        seen.add(id);
        for (var variant : variants) assertTrue(seen.add(variant.planId()), "two plans share an identifier");
    }

    @Test void eachPartOfAChangeIsPartOfTheIdentifier() {
        var added = EntityChangesTest.added();
        var ids = new java.util.HashSet<String>();
        var constraints = new org.ipro.design.ConstraintDraft("201", "", "");
        var variants = List.of(added,
            new org.ipro.design.FieldDraft(new UUID(7, 10), added.name(), added.label(), added.type(), added.columnName(), false, added.uiRequired(), added.constraints(), null, added.applications()),
            new org.ipro.design.FieldDraft(added.id(), "other", added.label(), added.type(), added.columnName(), false, added.uiRequired(), added.constraints(), null, added.applications()),
            new org.ipro.design.FieldDraft(added.id(), added.name(), added.label(), "INTEGER", added.columnName(), false, added.uiRequired(), added.constraints(), null, added.applications()),
            new org.ipro.design.FieldDraft(added.id(), added.name(), added.label(), added.type(), org.ipro.design.DraftText.explicit("col"), false, added.uiRequired(), added.constraints(), null, added.applications()),
            new org.ipro.design.FieldDraft(added.id(), added.name(), added.label(), added.type(), added.columnName(), false, org.ipro.design.FieldDraft.UiRequired.REQUIRED, added.constraints(), null, added.applications()),
            new org.ipro.design.FieldDraft(added.id(), added.name(), added.label(), added.type(), added.columnName(), false, added.uiRequired(), constraints, null, added.applications()),
            new org.ipro.design.FieldDraft(added.id(), added.name(), added.label(), added.type(), added.columnName(), false, added.uiRequired(), added.constraints(), new org.ipro.design.ReferenceDraft("a.U"), added.applications()),
            new org.ipro.design.FieldDraft(added.id(), added.name(), added.label(), added.type(), added.columnName(), false, added.uiRequired(), added.constraints(), null, new org.ipro.design.FieldApplications(true, false, true, false)));
        for (var field : variants)
            assertTrue(ids.add(plan("t", "a/A.java", BASE, NEW, Map.of(), List.of(new EntityChange.FieldAdded(field)), List.of(), List.of()).planId()), field.toString());
        assertTrue(ids.add(plan("t", "a/A.java", BASE, NEW, Map.of(), List.of(new EntityChange.LabelChanged(added.id(), "code", "Код", "Номер")), List.of(), List.of()).planId()));
        assertTrue(ids.add(plan("t", "a/A.java", BASE, NEW, Map.of(), List.of(new EntityChange.LabelChanged(added.id(), "code", "Код", "Номер 2")), List.of(), List.of()).planId()));
    }

    @Test void aPlanThatWasEditedByHandIsRefused() {
        var plan = plan();
        assertThrows(IllegalArgumentException.class, () -> new ModificationPlan(plan.planId(), plan.toolVersion(), plan.relativePath(),
            plan.baseSha256(), plan.baseContent(), plan.newContent(), plan.newSha256(), plan.dependencyHashes(), plan.changes(),
            List.of("DROP TABLE a;"), plan.warnings()));
        String other = "class A {\n    long x;\n}\n";
        assertThrows(IllegalArgumentException.class, () -> new ModificationPlan(plan.planId(), plan.toolVersion(), plan.relativePath(),
            plan.baseSha256(), plan.baseContent(), other, plan.newSha256(), plan.dependencyHashes(), plan.changes(), plan.schemaStatements(), plan.warnings()));
        assertThrows(IllegalArgumentException.class, () -> new ModificationPlan(plan.planId(), plan.toolVersion(), plan.relativePath(),
            plan.baseSha256(), plan.baseContent(), other, GenerationHash.sha256(other), plan.dependencyHashes(), plan.changes(), plan.schemaStatements(), plan.warnings()));
        assertThrows(IllegalArgumentException.class, () -> new ModificationPlan("em1-" + "0".repeat(64), plan.toolVersion(), plan.relativePath(),
            plan.baseSha256(), plan.baseContent(), plan.newContent(), plan.newSha256(), plan.dependencyHashes(), plan.changes(), plan.schemaStatements(), plan.warnings()));
        assertThrows(IllegalArgumentException.class, () -> new ModificationPlan("g1-" + "0".repeat(64), plan.toolVersion(), plan.relativePath(),
            plan.baseSha256(), plan.baseContent(), plan.newContent(), plan.newSha256(), plan.dependencyHashes(), plan.changes(), plan.schemaStatements(), plan.warnings()));
    }

    @Test void thePathMustBeRelative() {
        assertThrows(IllegalArgumentException.class, () -> plan("t", "/a/A.java", BASE, NEW, Map.of(), List.of(), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> plan("t", "a\\A.java", BASE, NEW, Map.of(), List.of(), List.of(), List.of()));
    }
}
