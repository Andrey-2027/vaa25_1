package org.ipro.design.source;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.ipro.design.ConstraintDraft;
import org.ipro.design.DraftProblem;
import org.ipro.design.DraftText;
import org.ipro.design.DraftValidator;
import org.ipro.design.EntityDraft;
import org.ipro.design.FieldApplications;
import org.ipro.design.FieldDraft;
import org.ipro.design.ReferenceDraft;
import org.ipro.design.existing.EntityChange;
import org.ipro.design.existing.EntityChanges;
import org.ipro.design.existing.ExistingEntity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The reader and the change validator together, on real model files. */
class ReadAndValidateChangeTest {
    private static final String CONSUMER = "com.example.entitytypes.model.";

    private static ProjectSources project;
    private static ExistingEntity read(String typeId) {
        var root = TestSources.existing(TestSources.CONSUMER);
        if (project == null) project = ProjectSources.scan(List.of(root));
        return JavaEntityReader.read(typeId, TestSources.read(root, project.type(typeId).orElseThrow().relativePath()), project);
    }
    private static EntityDraft plus(EntityDraft draft, FieldDraft field) {
        var fields = new ArrayList<>(draft.fields()); fields.add(field);
        return draft.withFields(fields);
    }
    private static FieldDraft scalar(String name, String type) {
        return new FieldDraft(UUID.nameUUIDFromBytes(name.getBytes()), name, "Новый", type, DraftText.auto(), false, FieldDraft.UiRequired.AUTO,
            type.equals("STRING") ? new ConstraintDraft("200", "", "") : ConstraintDraft.empty(), null, FieldApplications.root());
    }
    private ValidationOutcome validate(ExistingEntity entity, EntityDraft edited) {
        var context = project.designContext("consumer", "v1", null);
        var result = new DraftValidator().validateChange(entity, edited, context);
        return new ValidationOutcome(result.hasErrors(), result.problems());
    }
    private record ValidationOutcome(boolean errors, List<DraftProblem> problems) {}

    @Test void addingAFieldToACustomCatalogPasses() {
        var entity = read(CONSUMER + "CustomCatalog");
        var edited = plus(entity.draft(), scalar("address", "STRING"));
        var outcome = validate(entity, edited);
        assertFalse(outcome.errors(), outcome.problems().toString());
        assertEquals(1, EntityChanges.compare(entity.draft(), edited).changes().size());
    }

    @Test void aReferenceToAProductPassesAndToAnOrderLineDoesNot() {
        var entity = read(CONSUMER + "ExtendedProduct");
        var toProduct = new FieldDraft(new UUID(1, 1), "related", "Связанный", "REFERENCE", DraftText.auto(), false, FieldDraft.UiRequired.AUTO,
            ConstraintDraft.empty(), new ReferenceDraft(CONSUMER + "Product"), FieldApplications.root());
        assertFalse(validate(entity, plus(entity.draft(), toProduct)).errors());
        var toRow = new FieldDraft(new UUID(1, 2), "row", "Строка", "REFERENCE", DraftText.auto(), false, FieldDraft.UiRequired.AUTO,
            ConstraintDraft.empty(), new ReferenceDraft(CONSUMER + "OrderLine"), FieldApplications.root());
        var outcome = validate(entity, plus(entity.draft(), toRow));
        assertTrue(outcome.problems().stream().anyMatch(p -> p.code() == DraftProblem.Code.REFERENCE_UNAVAILABLE));
    }

    @Test void theBaseClassNamesAreReservedForAStandardCatalog() {
        var entity = read(CONSUMER + "Product");
        for (String name : List.of("code", "name", "displayName", "id", "version")) {
            var outcome = validate(entity, plus(entity.draft(), scalar(name, "STRING")));
            assertTrue(outcome.errors(), name);
            assertTrue(outcome.problems().stream().anyMatch(p -> p.code() == DraftProblem.Code.RESERVED_FIELD), name);
        }
        assertFalse(validate(entity, plus(entity.draft(), scalar("description", "STRING"))).errors());
    }

    @Test void aLockedFieldKeepsItsNameAndColumn() {
        var entity = read(CONSUMER + "ExtendedProduct");
        assertTrue(validate(entity, plus(entity.draft(), scalar("storageOnly", "STRING"))).errors());
        var sameColumn = new FieldDraft(new UUID(1, 3), "other", "Другое", "STRING", DraftText.explicit("storage_only"), false,
            FieldDraft.UiRequired.AUTO, new ConstraintDraft("10", "", ""), null, FieldApplications.root());
        assertTrue(validate(entity, plus(entity.draft(), sameColumn)).problems().stream().anyMatch(p -> p.code() == DraftProblem.Code.DUPLICATE_STORAGE_NAME));
    }

    @Test void aLabelChangeOnARealFieldIsTheOnlyChange() {
        var entity = read(CONSUMER + "CustomCatalog");
        var fields = new ArrayList<>(entity.draft().fields());
        var old = fields.getFirst();
        fields.set(0, new FieldDraft(old.id(), old.name(), "Иной ключ", old.type(), old.columnName(), old.serverRequired(), old.uiRequired(),
            old.constraints(), old.reference(), old.applications()));
        var set = EntityChanges.compare(entity.draft(), entity.draft().withFields(fields));
        assertEquals(List.of(new EntityChange.LabelChanged(old.id(), "externalKey", "Внешний ключ", "Иной ключ")), set.changes());
        assertFalse(validate(entity, entity.draft().withFields(fields)).errors());
    }

    @Test void aSectionRowCanBeOpenedButNotChanged() {
        var entity = read(CONSUMER + "OrderLine");
        assertTrue(validate(entity, entity.draft()).problems().isEmpty());
        var outcome = validate(entity, plus(entity.draft(), scalar("note", "STRING")));
        assertTrue(outcome.problems().stream().anyMatch(p -> p.code() == DraftProblem.Code.MODIFICATION_UNSUPPORTED));
    }
}
