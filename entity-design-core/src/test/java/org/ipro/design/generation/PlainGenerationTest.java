package org.ipro.design.generation;

import java.util.List;
import java.util.UUID;
import org.ipro.design.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlainGenerationTest {
    private static EntityDraft captionDraft() {
        var operations = new DraftOperations();
        var draft = operations.create(RecipeCatalog.CUSTOM_PLAIN_V2).withEntity(new EntityProperties(
            "RejectionReason", DraftText.explicit("example.generated"), "Причина отказа",
            EntityProperties.Titles.auto(), DraftText.explicit("rejection_reason"), null, "FILE"));
        draft = operations.addField(draft, null, "STRING");
        var id = draft.fields().getFirst().id();
        draft = operations.editField(draft, null, id, field -> new FieldDraft(id, "caption", "Подпись",
            "STRING", DraftText.auto(), true, FieldDraft.UiRequired.AUTO, new ConstraintDraft("120", "", ""),
            null, FieldApplications.root()));
        return draft.withPresentationField(id);
    }

    @Test void captionOnlyRootGeneratesJavaFormsAndDdlThroughDispatcher() {
        var draft = captionDraft();
        var context = SectionGenerationTest.context();
        var validator = new DraftValidator();
        assertTrue(validator.validate(draft.withPresentationField(null), context).problems().stream()
            .anyMatch(problem -> problem.code() == DraftProblem.Code.PLAIN_PRESENTATION_INVALID));
        var validation = validator.validate(draft, context);
        assertFalse(validation.hasErrors(), validation.problems().toString());
        var plan = new EntityJavaGenerator().generate(validation.checkedStructure().orElseThrow(), draft, context)
            .plan().orElseThrow();
        assertEquals(List.of("example/generated/RejectionReason.java", "example/generated/RejectionReasonFormConfiguration.java",
            "schema/RejectionReason.sql"), plan.files().stream().map(GenerationFile::relativePath).toList());
        String java = plan.files().getFirst().content();
        assertTrue(java.contains("extends BaseEntity") && java.contains("kind = EntityKind.PLAIN"));
        assertTrue(java.contains("@InstanceName(\"caption\")") && java.contains("@NotBlank") && java.contains("@FormSettingsOnly"));
        String forms = plan.files().get(1).content();
        assertTrue(forms.contains("@ListColumns({\"caption\"})") && forms.contains("@SelectionColumns({\"caption\"})"));
        String ddl = plan.files().getLast().content();
        assertTrue(ddl.contains("caption VARCHAR(120) NOT NULL") && ddl.contains("PRIMARY KEY") && ddl.contains("created_at"));
        assertFalse(ddl.contains("code ") || ddl.contains("name "));
        GenerationManifest.from(plan, EntityJavaGenerator.fingerprintProfile(draft.recipe()));
    }

    @Test void rejectsUnsupportedStructuresAndInvalidPresentationChoices() {
        var draft = captionDraft();
        var field = draft.fields().getFirst();
        rejects(draft.withFields(List.of()), DraftProblem.Code.PLAIN_PRESENTATION_INVALID);
        rejects(draft.withPresentationField(UUID.randomUUID()), DraftProblem.Code.PLAIN_PRESENTATION_INVALID);
        rejects(draft.withFields(List.of(field.withType("INTEGER"))), DraftProblem.Code.PLAIN_PRESENTATION_INVALID);
        rejects(draft.withFields(List.of(new FieldDraft(field.id(), field.name(), field.label(), field.type(),
            field.columnName(), false, field.uiRequired(), field.constraints(), null, field.applications()))),
            DraftProblem.Code.PLAIN_PRESENTATION_INVALID);
        rejects(draft.withFields(List.of(new FieldDraft(field.id(), field.name(), field.label(), field.type(),
            field.columnName(), true, field.uiRequired(), field.constraints(), null,
            new FieldApplications(true, false, true, false)))), DraftProblem.Code.PLAIN_PRESENTATION_INVALID);
        rejects(new DraftOperations().addField(draft, null, "REFERENCE"), DraftProblem.Code.UNSUPPORTED_FIELD_TYPE);
        rejects(new DraftOperations().addSection(draft), DraftProblem.Code.PLAIN_SECTION_UNSUPPORTED);
        rejects(draft.withOwner(new OwnerDraft("example.Owner", "parent", DraftText.auto(), "Владелец")),
            DraftProblem.Code.OWNER_UNSUPPORTED_RECIPE);
    }

    @Test void rejectsJavaNameCollisionsAndPostgresTypeLimitsBeforeGeneration() {
        var draft = captionDraft();
        var entity = draft.entity();
        for (String name : List.of("BaseEntity", "Entity", "String", "NotBlank")) {
            rejects(draft.withEntity(new EntityProperties(name, entity.packageName(), entity.label(),
                entity.titles(), entity.tableName(), null, entity.icon())), DraftProblem.Code.INVALID_JAVA_IDENTIFIER);
        }
        var caption = draft.fields().getFirst();
        var duplicateGetter = new FieldDraft(UUID.randomUUID(), "Caption", "Другой реквизит", "STRING",
            DraftText.explicit("other_caption"), false, FieldDraft.UiRequired.AUTO, caption.constraints(), null,
            FieldApplications.root());
        rejects(draft.withFields(List.of(caption, duplicateGetter)), DraftProblem.Code.DUPLICATE_NAME);
        var tooWide = new FieldDraft(UUID.randomUUID(), "amount", "Сумма", "DECIMAL", DraftText.auto(), false,
            FieldDraft.UiRequired.AUTO, new ConstraintDraft("", "1001", "2"), null, FieldApplications.root());
        rejects(draft.withFields(List.of(caption, tooWide)), DraftProblem.Code.INVALID_CONSTRAINT);
        var tooLong = new FieldDraft(caption.id(), caption.name(), caption.label(), "STRING", DraftText.auto(), true,
            FieldDraft.UiRequired.AUTO, new ConstraintDraft("10485761", "", ""), null, FieldApplications.root());
        rejects(draft.withFields(List.of(tooLong)), DraftProblem.Code.INVALID_CONSTRAINT);
    }

    @Test void generatorRejectsStaleValidationAndARequiredFieldMarkedOptionalInTheForm() {
        var draft = captionDraft();
        var context = SectionGenerationTest.context();
        var validator = new DraftValidator();
        var checked = validator.validate(draft, context).checkedStructure().orElseThrow();
        var generator = new EntityJavaGenerator();
        var stale = generator.generate(checked, draft.withPresentationField(null), context);
        assertTrue(stale.plan().isEmpty());
        assertEquals(GenerationDiagnostic.Code.STALE_CHECKED_STRUCTURE, stale.diagnostics().getFirst().code());

        var field = draft.fields().getFirst();
        var optional = new FieldDraft(field.id(), field.name(), field.label(), field.type(), field.columnName(),
            true, FieldDraft.UiRequired.OPTIONAL, field.constraints(), null, field.applications());
        var changed = draft.withFields(List.of(optional));
        var result = generator.generate(validator.validate(changed, context).checkedStructure().orElseThrow(),
            changed, context);
        assertTrue(result.plan().isEmpty());
        assertEquals(GenerationDiagnostic.Code.UNREPRESENTABLE_REQUIRED_MODE, result.diagnostics().getFirst().code());
    }

    private static void rejects(EntityDraft draft, DraftProblem.Code code) {
        var result = new DraftValidator().validate(draft, SectionGenerationTest.context());
        assertTrue(result.checkedStructure().isEmpty(), draft.toString());
        assertTrue(result.problems().stream().anyMatch(problem -> problem.code() == code), result.problems().toString());
    }
}
