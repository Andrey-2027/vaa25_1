package org.ipro.design;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.ipro.design.existing.EntityChangesTest;
import org.ipro.design.existing.EntityShape;
import org.ipro.design.existing.ExistingEntity;
import org.ipro.design.existing.LockedField;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.ipro.design.DraftProblem.Code.*;
import static org.ipro.design.existing.EntityChangesTest.*;

class ExistingEntityValidationTest {
    private final DraftValidator validator = new DraftValidator();

    private static final Set<String> INHERITED = Set.of("id", "version", "createdAt", "modifiedAt", "createdBy", "modifiedBy");

    /** A Workshop-like entity: no recipe, no code/name from a standard base, its own table, a locked field. */
    private static ExistingEntity existing(EntityShape shape, Set<String> methods, EntityDraft draft) {
        return new ExistingEntity("a.Workshop", "a/Workshop.java", "0".repeat(64), "workshop", shape, draft,
            List.of(new LockedField("legacy", "legacy_col", "", "String", "NO_FIELD_METADATA")), INHERITED, methods);
    }
    private static ExistingEntity existing(EntityShape shape, Set<String> methods) { return existing(shape, methods, base()); }
    private static ExistingEntity existing() { return existing(EntityShape.ROOT, Set.of("getCode", "setCode", "getUnit", "setUnit", "getDisplayName")); }

    private ValidationResult check(ExistingEntity existing, EntityDraft edited) {
        return validator.validateChange(existing, edited, Fixtures.context());
    }
    private static EntityDraft plus(FieldDraft field) { return with(base(), list -> { list.add(field); return list; }); }
    private static FieldDraft field(String name, String column) {
        var f = added();
        return new FieldDraft(f.id(), name, f.label(), f.type(), column == null ? DraftText.auto() : DraftText.explicit(column), f.serverRequired(),
            f.uiRequired(), f.constraints(), f.reference(), f.applications());
    }
    private static void problem(ValidationResult result, DraftProblem.Code code, UUID field, String property) {
        assertTrue(result.problems().stream().anyMatch(p -> p.code() == code && Objects.equals(field, p.target().fieldId())
            && p.target().propertyKey().equals(property)), () -> code + " / " + property + ": " + result.problems());
    }

    @Test void anUnchangedDraftHasNoDiagnosticsAboutCodeOrPrerequisitesOrItsOwnTable() {
        var result = check(existing(), base());
        assertTrue(result.problems().isEmpty(), result.problems().toString());
        assertTrue(result.checkedStructure().isEmpty());
    }

    @Test void aValidNewFieldPasses() {
        var result = check(existing(), plus(added()));
        assertFalse(result.hasErrors(), result.problems().toString());
    }

    @Test void aNewFieldMayNotTakeAnInheritedLockedOrExistingNameOrColumn() {
        var id = added().id();
        for (String name : List.of("version", "createdAt", "legacy", "code", "unit"))
            problem(check(existing(), plus(field(name, null))), RESERVED_FIELD, id, "name");
        problem(check(existing(), plus(field("Code", null))), RESERVED_FIELD, id, "name");
        problem(check(existing(), plus(field("legacyKey", "legacy_col"))), DUPLICATE_STORAGE_NAME, id, "columnName");
        problem(check(existing(), plus(field("owner", "unit_id"))), DUPLICATE_STORAGE_NAME, id, "columnName");
        problem(check(existing(), plus(field("changed", "created_at"))), DUPLICATE_STORAGE_NAME, id, "columnName");
    }

    @Test void aNewFieldFollowsTheCommonFieldRules() {
        var id = added().id();
        problem(check(existing(), plus(field("class", null))), JAVA_KEYWORD, id, "name");
        problem(check(existing(), plus(field("Class", null))), RESERVED_FIELD, id, "name");
        problem(check(existing(), plus(field("limit", null))), INVALID_STORAGE_NAME, id, "columnName");
        assertFalse(check(existing(), plus(field("limit", "limit_value"))).hasErrors());
        var tooLong = new FieldDraft(id, "x", "X", "STRING", DraftText.auto(), false, FieldDraft.UiRequired.AUTO, new ConstraintDraft("0", "", ""), null, FieldApplications.root());
        problem(check(existing(), plus(tooLong)), INVALID_CONSTRAINT, id, "constraints.length");
    }

    @Test void aReferenceToAnOwnedRowIsRefused() {
        var row = new ReferenceTargetDescriptor("a.Row", "Row", "OWNED_ROW", "java.lang.Long", ReferenceTargetDescriptor.Fact.NO,
            ReferenceTargetDescriptor.Fact.NO, ReferenceTargetDescriptor.Eligibility.UNSUPPORTED, "row");
        var base = Fixtures.context();
        var targets = new java.util.HashMap<>(base.referenceTargets()); targets.put(row.typeId(), row);
        var context = new ProjectDesignContext(base.projectId(), base.version(), base.defaultPackage(), base.existingTypes(), base.existingTables(),
            base.subsystemIds(), base.iconIds(), targets, true);
        var ref = new FieldDraft(added().id(), "line", "Строка", "REFERENCE", DraftText.auto(), false, FieldDraft.UiRequired.AUTO,
            ConstraintDraft.empty(), new ReferenceDraft("a.Row"), FieldApplications.root());
        problem(validator.validateChange(existing(), plus(ref), context), REFERENCE_UNAVAILABLE, ref.id(), "reference.typeId");
        var good = new FieldDraft(ref.id(), "line", "Строка", "REFERENCE", DraftText.auto(), false, FieldDraft.UiRequired.AUTO,
            ConstraintDraft.empty(), new ReferenceDraft("org.ip.model.UnitOfMeasurement"), FieldApplications.root());
        assertFalse(validator.validateChange(existing(), plus(good), context).hasErrors());
    }

    @Test void anUnchangedFieldWithAnUnusualColumnIsNotJudged() {
        // The file already maps a field to a reserved word; that is the file's business until the field changes.
        var odd = new FieldDraft(new UUID(7, 5), "keyed", "Ключ", "STRING", DraftText.explicit("value"), false, FieldDraft.UiRequired.AUTO,
            new ConstraintDraft("10", "", ""), null, FieldApplications.root());
        var draft = with(base(), list -> { list.add(odd); return list; });
        var existing = existing(EntityShape.ROOT, Set.of(), draft);
        assertTrue(check(existing, draft).problems().isEmpty());
        assertFalse(check(existing, with(draft, list -> { list.add(added()); return list; })).hasErrors());
    }

    @Test void aRequiredNewFieldNeedsADecisionAboutOldRows() {
        var f = added();
        var required = new FieldDraft(f.id(), f.name(), f.label(), f.type(), f.columnName(), true, FieldDraft.UiRequired.REQUIRED, f.constraints(), null, f.applications());
        problem(check(existing(), plus(required)), MODIFICATION_UNSUPPORTED, f.id(), "serverRequired");
    }

    @Test void onlyARootCanBeChanged() {
        for (var shape : List.of(EntityShape.SECTION_ROW, EntityShape.REGISTER)) {
            problem(check(existing(shape, Set.of()), plus(added())), MODIFICATION_UNSUPPORTED, null, "shape");
            assertTrue(check(existing(shape, Set.of()), base()).problems().isEmpty(), "opening a " + shape + " is fine");
        }
        problem(check(existing(EntityShape.UNRESOLVED_BASE, Set.of()), plus(added())), PROJECT_FACT_UNKNOWN, null, "context.baseChain");
        assertTrue(check(existing(EntityShape.UNRESOLVED_BASE, Set.of()), base()).problems().isEmpty());
    }

    @Test void unsupportedDifferencesSurfaceThroughTheValidator() {
        var renamed = replace(base(), CODE, f -> f.withName("number"));
        problem(check(existing(), renamed), MODIFICATION_UNSUPPORTED, CODE, "name");
    }

    @Test void aLabelChangeChecksOnlyTheLabel() {
        var changed = replace(base(), CODE, f -> new FieldDraft(f.id(), f.name(), "Номер", f.type(), f.columnName(), f.serverRequired(),
            f.uiRequired(), f.constraints(), f.reference(), f.applications()));
        assertTrue(check(existing(), changed).problems().isEmpty());
        var blank = replace(base(), CODE, f -> new FieldDraft(f.id(), f.name(), "  ", f.type(), f.columnName(), f.serverRequired(),
            f.uiRequired(), f.constraints(), f.reference(), f.applications()));
        problem(check(existing(), blank), VALUE_REQUIRED, CODE, "label");
    }

    @Test void aTakenAccessorOfAnySignatureRejectsTheField() {
        var id = added().id();
        var manual = existing(EntityShape.ROOT, Set.of("setAddress", "getCode"));
        problem(check(manual, plus(field("address", null))), RESERVED_FIELD, id, "name");
        var inSubclass = existing(EntityShape.ROOT, Set.of("getAddress"));
        problem(check(inSubclass, plus(field("address", null))), RESERVED_FIELD, id, "name");
        var predicate = existing(EntityShape.ROOT, Set.of("isAddress"));
        problem(check(predicate, plus(field("address", null))), RESERVED_FIELD, id, "name");
        var platform = existing(EntityShape.ROOT, Set.of("getDisplayName"));
        problem(check(platform, plus(field("displayName", null))), RESERVED_FIELD, id, "name");
        assertFalse(check(existing(EntityShape.ROOT, Set.of("getCode")), plus(field("address", null))).hasErrors());
    }

    @Test void creationValidationIsUnchanged() {
        var result = validator.validate(Fixtures.valid(), Fixtures.context());
        assertFalse(result.hasErrors());
        assertTrue(result.checkedStructure().isPresent());
    }
}
