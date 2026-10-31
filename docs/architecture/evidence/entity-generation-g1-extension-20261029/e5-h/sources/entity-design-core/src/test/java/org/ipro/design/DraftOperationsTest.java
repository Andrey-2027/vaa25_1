package org.ipro.design;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.ipro.design.Fixtures.*;

class DraftOperationsTest {
    @Test void createIsUnfinishedWithoutPersistentIdentity() {
        UUID id = UUID.randomUUID();
        var draft = new DraftOperations(() -> id).create();
        assertEquals(id, draft.draftId()); assertEquals(RecipeCatalog.STANDARD_CATALOG, draft.recipe());
        assertEquals("FILE", draft.entity().icon()); assertTrue(draft.fields().isEmpty()); assertTrue(draft.sections().isEmpty());
        assertEquals(DraftText.auto(), draft.entity().packageName());
        assertTrue(new DraftValidator().validate(draft, context()).hasErrors());
        assertEquals(List.of("builtin:code", "builtin:name"), RecipeCatalog.inheritedFields().stream().map(RecipeCatalog.InheritedField::key).toList());
    }
    @Test void renamesAndReordersKeepIdentityReferencesAndOldSnapshots() {
        var ops = new DraftOperations(); var original = valid();
        var first = original.fields().getFirst();
        var renamed = ops.editField(original, null, first.id(), f -> f.withName("measurement"));
        var moved = ops.moveField(renamed, null, first.id(), 1);
        assertEquals("unit", original.fields().getFirst().name());
        assertEquals(first.id(), moved.fields().get(1).id()); assertEquals(first.reference(), moved.fields().get(1).reference());
        assertEquals(original.draftId(), moved.draftId());
        var section = original.sections().getFirst(); var rowField = section.fields().getFirst();
        var rows = ops.editField(original, section.id(), rowField.id(), f -> f.withName("product"));
        rows = ops.moveField(rows, section.id(), rowField.id(), 1);
        rows = ops.moveSection(rows, section.id(), 1);
        assertEquals(rowField.id(), rows.sections().get(1).fields().get(1).id());
        assertEquals(rowField.reference(), rows.sections().get(1).fields().get(1).reference());
        var removed = ops.removeSection(rows, section.id());
        assertEquals(1, removed.sections().size()); assertEquals(2, rows.sections().size());
        assertEquals(1, ops.removeField(original, null, first.id()).fields().size());
    }
    @Test void sectionAndEntityRenamesPreserveAllNestedIds() {
        var d = valid(); var e = d.entity(); var ops = new DraftOperations();
        var renamed = d.withEntity(new EntityProperties("RenamedCard", e.packageName(), "Другое имя", e.titles(), e.tableName(), e.subsystemId(), e.icon()));
        var s = d.sections().getFirst();
        renamed = ops.editSection(renamed, s.id(), old -> new SectionDraft(old.id(), "Переименовано", "RenamedRow", old.packageName(), old.tableName(), old.parentFieldName(), old.parentColumnName(), old.fields()));
        assertEquals(d.draftId(), renamed.draftId()); assertEquals(s.id(), renamed.sections().getFirst().id());
        assertEquals(s.fields(), renamed.sections().getFirst().fields());
    }
    @Test void addInitializesOnlyNewFieldsAndDoesNotClearOnTypeChange() {
        var ops = new DraftOperations(); var d = ops.addField(valid(), null, "STRING");
        var added = d.fields().getLast(); assertEquals(new ConstraintDraft("255", "", ""), added.constraints());
        var changed = ops.editField(d, null, added.id(), f -> f.withType("DECIMAL"));
        assertEquals(added.constraints(), changed.fields().getLast().constraints());
        assertEquals(added.id(), changed.fields().getLast().id());
        assertEquals(ConstraintDraft.empty(), RecipeCatalog.initialConstraints("GEO_POINT"));
        var withSection = ops.addSection(d); var sectionId = withSection.sections().getLast().id();
        var withDecimal = ops.addField(withSection, sectionId, "DECIMAL");
        assertEquals(new ConstraintDraft("", "19", "2"), withDecimal.sections().getLast().fields().getFirst().constraints());
        assertEquals(FieldApplications.row(), withDecimal.sections().getLast().fields().getFirst().applications());
    }
    @Test void operationsRejectMissingAmbiguousOrCrossContainerTargetsAndIdentityChanges() {
        var ops = new DraftOperations(); var d = valid(); var f = d.fields().getFirst(); var s = d.sections().getFirst();
        assertThrows(IllegalArgumentException.class, () -> ops.removeField(d, s.id(), f.id()));
        assertThrows(IllegalArgumentException.class, () -> ops.moveField(d, null, f.id(), -1));
        assertThrows(IllegalArgumentException.class, () -> ops.moveSection(d, s.id(), 2));
        assertThrows(IllegalArgumentException.class, () -> ops.removeSection(d, UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> ops.removeField(d.withFields(List.of(f, f)), null, f.id()));
        assertThrows(IllegalArgumentException.class, () -> ops.editField(d, null, f.id(), old -> new FieldDraft(UUID.randomUUID(), old.name(), old.label(), old.type(), old.columnName(), old.serverRequired(), old.uiRequired(), old.constraints(), old.reference(), old.applications())));
        assertThrows(IllegalArgumentException.class, () -> ops.editSection(d, s.id(), old -> old.withFields(List.of())));
        assertThrows(IllegalArgumentException.class, () -> new DraftOperations(f::id).addSection(d));
    }
    @Test void containersDefensivelyCopyAndCannotBeMutated() {
        var source = valid(); var mutable = new ArrayList<>(source.fields()); var d = source.withFields(mutable); mutable.clear();
        assertEquals(2, d.fields().size());
        assertThrows(UnsupportedOperationException.class, () -> d.fields().clear());
        assertThrows(UnsupportedOperationException.class, () -> d.sections().getFirst().fields().clear());
        assertThrows(UnsupportedOperationException.class, () -> context().referenceTargets().clear());
        assertThrows(UnsupportedOperationException.class, () -> context().existingTypes().values().clear());
        assertThrows(IllegalArgumentException.class, () -> new DraftText(DraftText.Mode.AUTO, "stale"));
    }
    @Test void jsonFixturesHydrateVerbatimWithoutSemanticCoercion() throws Exception {
        for (String name : List.of("unfinished", "catalog-two-sections", "semantic-errors")) {
            var d = draft(name);
            assertEquals(d, JSON.readValue(JSON.writeValueAsBytes(d), EntityDraft.class));
        }
        var invalid = draft("semantic-errors");
        assertEquals("12x", invalid.fields().get(1).constraints().precision());
        assertEquals("-", invalid.fields().get(1).constraints().scale());
        assertEquals("GEO_POINT", invalid.sections().get(1).fields().getFirst().type());
    }
}
