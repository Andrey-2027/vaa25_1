package org.ipro.design.existing;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.ipro.design.ConstraintDraft;
import org.ipro.design.DraftProblem;
import org.ipro.design.DraftText;
import org.ipro.design.EntityDraft;
import org.ipro.design.EntityProperties;
import org.ipro.design.FieldApplications;
import org.ipro.design.FieldDraft;
import org.ipro.design.RecipeCatalog;
import org.ipro.design.ReferenceDraft;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class EntityChangesTest {
    public static final UUID DRAFT = new UUID(7, 7), CODE = new UUID(7, 1), UNIT = new UUID(7, 2);

    public static FieldDraft code() {
        return new FieldDraft(CODE, "code", "Код", "STRING", DraftText.auto(), true, FieldDraft.UiRequired.REQUIRED,
            new ConstraintDraft("20", "", ""), null, FieldApplications.root());
    }
    public static FieldDraft unit() {
        return new FieldDraft(UNIT, "unit", "Единица", "REFERENCE", DraftText.explicit("unit_id"), false, FieldDraft.UiRequired.AUTO,
            ConstraintDraft.empty(), new ReferenceDraft("a.Unit"), new FieldApplications(true, true, false, false));
    }
    public static EntityDraft base() {
        var entity = new EntityProperties("Workshop", DraftText.explicit("a"), "Цех", EntityProperties.Titles.auto(),
            DraftText.explicit("workshop"), null, "FILE");
        return new EntityDraft(2, RecipeCatalog.EXISTING_ENTITY, DRAFT, entity, List.of(code(), unit()), List.of());
    }
    public static EntityDraft with(EntityDraft draft, UnaryOperator<List<FieldDraft>> fields) {
        return draft.withFields(fields.apply(new ArrayList<>(draft.fields())));
    }
    public static EntityDraft replace(EntityDraft draft, UUID id, UnaryOperator<FieldDraft> edit) {
        return with(draft, list -> { list.replaceAll(f -> f.id().equals(id) ? edit.apply(f) : f); return list; });
    }
    public static FieldDraft added() {
        return new FieldDraft(new UUID(7, 9), "address", "Адрес", "STRING", DraftText.auto(), false, FieldDraft.UiRequired.AUTO,
            new ConstraintDraft("200", "", ""), null, FieldApplications.root());
    }
    private static void unsupported(ChangeSet set, UUID field, String property) {
        assertTrue(set.problems().stream().anyMatch(p -> p.code() == DraftProblem.Code.MODIFICATION_UNSUPPORTED
                && java.util.Objects.equals(field, p.target().fieldId()) && p.target().propertyKey().equals(property)),
            () -> property + ": " + set.problems());
        assertTrue(set.hasErrors());
    }

    @Test void identicalDraftsHaveNoChanges() {
        var set = EntityChanges.compare(base(), base());
        assertTrue(set.isEmpty());
        assertFalse(set.hasErrors());
    }

    @Test void aNewFieldIsAdded() {
        var set = EntityChanges.compare(base(), with(base(), list -> { list.add(added()); return list; }));
        assertEquals(List.of(new EntityChange.FieldAdded(added())), set.changes());
        assertTrue(set.problems().isEmpty());
    }

    @Test void aNewLabelIsChanged() {
        var set = EntityChanges.compare(base(), replace(base(), CODE, f -> new FieldDraft(f.id(), f.name(), "Номер", f.type(), f.columnName(),
            f.serverRequired(), f.uiRequired(), f.constraints(), f.reference(), f.applications())));
        assertEquals(List.of(new EntityChange.LabelChanged(CODE, "code", "Код", "Номер")), set.changes());
        assertTrue(set.problems().isEmpty());
    }

    @Test void additionAndLabelChangeComeTogether() {
        var edited = with(replace(base(), CODE, f -> new FieldDraft(f.id(), f.name(), "Номер", f.type(), f.columnName(),
            f.serverRequired(), f.uiRequired(), f.constraints(), f.reference(), f.applications())), list -> { list.add(added()); return list; });
        var set = EntityChanges.compare(base(), edited);
        assertEquals(2, set.changes().size());
        assertTrue(set.problems().isEmpty());
    }

    @Test void removalIsRefused() {
        unsupported(EntityChanges.compare(base(), with(base(), list -> { list.removeIf(f -> f.id().equals(UNIT)); return list; })), UNIT, "field");
    }

    @Test void everyOtherFieldDifferenceIsRefusedAtItsProperty() {
        record Case(String property, UnaryOperator<FieldDraft> edit) {}
        var cases = List.of(
            new Case("name", f -> f.withName("number")),
            new Case("type", f -> f.withType("INTEGER")),
            new Case("serverRequired", f -> new FieldDraft(f.id(), f.name(), f.label(), f.type(), f.columnName(), !f.serverRequired(), f.uiRequired(), f.constraints(), f.reference(), f.applications())),
            new Case("uiRequired", f -> new FieldDraft(f.id(), f.name(), f.label(), f.type(), f.columnName(), f.serverRequired(), FieldDraft.UiRequired.OPTIONAL, f.constraints(), f.reference(), f.applications())),
            new Case("constraints.length", f -> new FieldDraft(f.id(), f.name(), f.label(), f.type(), f.columnName(), f.serverRequired(), f.uiRequired(), new ConstraintDraft("30", "", ""), f.reference(), f.applications())),
            new Case("constraints.precision", f -> new FieldDraft(f.id(), f.name(), f.label(), f.type(), f.columnName(), f.serverRequired(), f.uiRequired(), new ConstraintDraft("20", "10", ""), f.reference(), f.applications())),
            new Case("constraints.scale", f -> new FieldDraft(f.id(), f.name(), f.label(), f.type(), f.columnName(), f.serverRequired(), f.uiRequired(), new ConstraintDraft("20", "", "2"), f.reference(), f.applications())),
            new Case("columnName", f -> new FieldDraft(f.id(), f.name(), f.label(), f.type(), DraftText.explicit("other"), f.serverRequired(), f.uiRequired(), f.constraints(), f.reference(), f.applications())),
            new Case("reference.typeId", f -> new FieldDraft(f.id(), f.name(), f.label(), f.type(), f.columnName(), f.serverRequired(), f.uiRequired(), f.constraints(), new ReferenceDraft("a.Other"), f.applications())),
            new Case("applications.form", f -> new FieldDraft(f.id(), f.name(), f.label(), f.type(), f.columnName(), f.serverRequired(), f.uiRequired(), f.constraints(), f.reference(), new FieldApplications(!f.applications().form(), f.applications().list(), f.applications().selection(), false))),
            new Case("applications.list", f -> new FieldDraft(f.id(), f.name(), f.label(), f.type(), f.columnName(), f.serverRequired(), f.uiRequired(), f.constraints(), f.reference(), new FieldApplications(f.applications().form(), !f.applications().list(), f.applications().selection(), false))),
            new Case("applications.selection", f -> new FieldDraft(f.id(), f.name(), f.label(), f.type(), f.columnName(), f.serverRequired(), f.uiRequired(), f.constraints(), f.reference(), new FieldApplications(f.applications().form(), f.applications().list(), !f.applications().selection(), false))),
            new Case("applications.table", f -> new FieldDraft(f.id(), f.name(), f.label(), f.type(), f.columnName(), f.serverRequired(), f.uiRequired(), f.constraints(), f.reference(), new FieldApplications(f.applications().form(), f.applications().list(), f.applications().selection(), true))));
        for (var c : cases) {
            var set = EntityChanges.compare(base(), replace(base(), UNIT, c.edit()));
            unsupported(set, UNIT, c.property());
            assertTrue(set.changes().isEmpty(), c.property());
        }
    }

    @Test void reorderingExistingFieldsIsRefusedButPlacementOfNewFieldsIsNot() {
        var reversed = with(base(), list -> { java.util.Collections.reverse(list); return list; });
        unsupported(EntityChanges.compare(base(), reversed), null, "order");
        var first = with(base(), list -> { list.addFirst(added()); return list; });
        var set = EntityChanges.compare(base(), first);
        assertTrue(set.problems().isEmpty());
        assertEquals(1, set.changes().size());
    }

    @Test void classPackageTableAndOtherEntityPropertiesAreRefused() {
        record Case(String property, UnaryOperator<EntityProperties> edit) {}
        var cases = List.of(
            new Case("className", e -> new EntityProperties("Other", e.packageName(), e.label(), e.titles(), e.tableName(), e.subsystemId(), e.icon())),
            new Case("packageName", e -> new EntityProperties(e.className(), DraftText.explicit("b"), e.label(), e.titles(), e.tableName(), e.subsystemId(), e.icon())),
            new Case("tableName", e -> new EntityProperties(e.className(), e.packageName(), e.label(), e.titles(), DraftText.explicit("other"), e.subsystemId(), e.icon())),
            new Case("label", e -> new EntityProperties(e.className(), e.packageName(), "Другое", e.titles(), e.tableName(), e.subsystemId(), e.icon())),
            new Case("titles.list", e -> new EntityProperties(e.className(), e.packageName(), e.label(), new EntityProperties.Titles(DraftText.explicit("x"), e.titles().item(), e.titles().selection()), e.tableName(), e.subsystemId(), e.icon())),
            new Case("subsystemId", e -> new EntityProperties(e.className(), e.packageName(), e.label(), e.titles(), e.tableName(), "s", e.icon())),
            new Case("icon", e -> new EntityProperties(e.className(), e.packageName(), e.label(), e.titles(), e.tableName(), e.subsystemId(), "COGS")));
        for (var c : cases) {
            var edited = base().withEntity(c.edit().apply(base().entity()));
            unsupported(EntityChanges.compare(base(), edited), null, c.property());
        }
    }

    @Test void recipeAndStructureOutsideFieldsAreRefused() {
        var other = new EntityDraft(2, RecipeCatalog.STANDARD_CATALOG_V2, DRAFT, base().entity(), base().fields(), List.of());
        unsupported(EntityChanges.compare(base(), other), null, "recipe");
        var section = new org.ipro.design.SectionDraft(new UUID(7, 20), "Строки", "Row", DraftText.auto(), DraftText.auto(), DraftText.auto(), DraftText.auto(), List.of());
        unsupported(EntityChanges.compare(base(), base().withSections(List.of(section))), null, "sections");
        unsupported(EntityChanges.compare(base(), base().withOwner(new org.ipro.design.OwnerDraft("owner", "Владелец", DraftText.auto(), "a.Unit"))), null, "owner");
    }

    @Test void aRepeatedIdIsRefused() {
        var twice = with(base(), list -> { list.add(unit()); return list; });
        unsupported(EntityChanges.compare(base(), twice), UNIT, "id");
    }
}
