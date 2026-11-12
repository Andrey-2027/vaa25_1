package org.ipro.design.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.ipro.design.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** R2: register drafts are created, validated, checked and transferred without changing v1/v2 envelopes. */
class RegisterDraftJsonTest {
    private final DraftJsonCodec codec = new DraftJsonCodec();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path contracts = Path.of("../docs/architecture/contracts/register");
    private final DraftValidator validator = new DraftValidator();

    @Test void separateFormRecipesRoundTripWithoutImplicitUpgrade() throws Exception {
        var old = codec.decode(Files.readString(contracts.resolve("register-draft-day.json")));
        for (var recipe : List.of(RecipeCatalog.INDEPENDENT_REGISTER_V3, RecipeCatalog.INDEPENDENT_REGISTER_RLS_V4,
                RecipeCatalog.INDEPENDENT_REGISTER_V5, RecipeCatalog.INDEPENDENT_REGISTER_RLS_V6)) {
            boolean rls = RecipeCatalog.isRlsRegisterRecipe(recipe);
            var draft = new EntityDraft(rls ? 4 : 3, recipe, old.draftId(), old.entity(), old.fields(), old.sections(), null,
                rls ? old.register().withRls(new RegisterRlsDraft("REGISTER_ITEM", old.register().dimensions().getFirst())) : old.register());
            assertEquals(draft, codec.decode(codec.encode(draft)));
            var wrong = (ObjectNode)mapper.readTree(codec.encode(draft));
            ((ObjectNode)wrong.get("recipe")).put("version", 999);
            assertThrows(DraftJsonException.class, () -> codec.decode(wrong.toString()));
        }
        assertEquals(RecipeCatalog.INDEPENDENT_REGISTER, codec.decode(codec.encode(old)).recipe());
    }
    @Test void explicitRlsEnvelopeRoundTripsWithoutChangingCapturedEnvelope() throws Exception {
        var old=codec.decode(Files.readString(contracts.resolve("register-draft-day.json")));
        var policy=new RegisterRlsDraft("REGISTER_ITEM",old.register().dimensions().getFirst());
        var draft=new EntityDraft(4,RecipeCatalog.INDEPENDENT_REGISTER_RLS,old.draftId(),old.entity(),old.fields(),old.sections(),null,old.register().withRls(policy));
        assertEquals(draft,codec.decode(codec.encode(draft)));
        assertFalse(mapper.readTree(codec.encode(old)).get("register").has("rls"));
        var incomplete=draft.withRegister(draft.register().withRls(new RegisterRlsDraft("",null)));
        assertEquals(incomplete,codec.decode(codec.encode(incomplete)));
        assertEquals(draft.withRegister(draft.register().withRls(null)),codec.decode(codec.encode(draft.withRegister(draft.register().withRls(null)))));
    }
    @Test void rlsCannotCrossEnvelopeOrRecipeVersionBoundaries() throws Exception {
        var old=codec.decode(Files.readString(contracts.resolve("register-draft-day.json")));
        var policy=new RegisterRlsDraft("REGISTER_ITEM",old.register().dimensions().getFirst());
        assertThrows(DraftJsonException.class,() -> codec.encode(old.withRegister(old.register().withRls(policy))));
        var current=new EntityDraft(4,RecipeCatalog.INDEPENDENT_REGISTER_RLS,old.draftId(),old.entity(),old.fields(),old.sections(),null,old.register().withRls(policy));
        var tree=(ObjectNode)mapper.readTree(codec.encode(current));
        tree.put("schemaVersion",3);
        assertThrows(DraftJsonException.class,() -> codec.decode(tree.toString()));
        tree.put("schemaVersion",4);((ObjectNode)tree.get("recipe")).put("id","independent-register-v1").put("version",1);
        assertThrows(DraftJsonException.class,() -> codec.decode(tree.toString()));
    }

    @Test
    void noneFixtureValidatesAndRoundTripsWithoutRepair() throws Exception {
        var draft = codec.decode(Files.readString(contracts.resolve("register-draft-none.json")));
        assertEquals(3, draft.schemaVersion());
        assertEquals(RegisterDraft.NONE, draft.register().period());
        assertEquals(2, draft.fields().size());
        assertEquals(draft, codec.decode(codec.encode(draft)));
        assertEquals(mapper.readTree(Files.readString(contracts.resolve("register-draft-none.json"))),
            mapper.readTree(codec.encode(draft)));

        var result = validator.validate(draft, context());
        assertFalse(result.hasErrors(), () -> result.problems().toString());
        var register = result.checkedStructure().orElseThrow().register();
        assertNotNull(register);
        assertEquals(List.of("region_code"), register.rowKeyColumns());
        assertEquals(List.of("region_code"), register.dimensionGroupColumns());
        assertFalse(register.periodic());
        assertNull(register.periodColumn());
        assertEquals(List.of("regionCode"), register.dimensions().stream().map(CheckedStructure.RegisterRole::name).toList());
        assertEquals(List.of("rate"), register.resources().stream().map(CheckedStructure.RegisterRole::name).toList());
    }

    @Test
    void dayFixtureKeepsBuiltinPeriodLastInKey() throws Exception {
        var draft = codec.decode(Files.readString(contracts.resolve("register-draft-day.json")));
        var result = validator.validate(draft, context());
        assertFalse(result.hasErrors(), () -> result.problems().toString());
        var register = result.checkedStructure().orElseThrow().register();
        assertEquals(RegisterDraft.DAY, register.period());
        assertEquals("period_date", register.periodColumn());
        assertTrue(register.periodic());
        assertEquals(List.of("item_id", "period_date"), register.rowKeyColumns());
        assertEquals(List.of("item_id"), register.dimensionGroupColumns());
        assertEquals("item", register.dimensions().getFirst().name());
        assertEquals("org.ip.model.Nomenclature", register.dimensions().getFirst().referenceTypeId());
    }

    @Test
    void specificationMatrixMatchesTheSemanticValidator() throws Exception {
        var cases = mapper.readTree(Files.readString(contracts.resolve("register-draft-cases.json")));
        Map<String, String> fixtures = new HashMap<>();
        fixtures.put("NONE", Files.readString(contracts.resolve("register-draft-none.json")));
        fixtures.put("DAY", Files.readString(contracts.resolve("register-draft-day.json")));
        int checked = 0;
        for (var testCase : cases.get("cases")) {
            String id = testCase.get("id").asText();
            ObjectNode tree = (ObjectNode) mapper.readTree(fixtures.get(testCase.get("from").asText()));
            for (var edit : testCase.get("edits")) apply(tree, edit);
            EntityDraft draft = codec.decode(tree.toString());
            var result = validator.validate(draft, context());
            var actual = new HashSet<String>();
            result.problems().stream().filter(p -> p.severity() == DraftProblem.Severity.ERROR)
                .forEach(p -> actual.add(p.code() + "@" + p.target().propertyKey()));
            boolean valid = testCase.path("expect").path("valid").asBoolean();
            assertEquals(valid, !result.hasErrors(), () -> id + " expected valid=" + valid + " actual=" + actual);
            for (var expected : testCase.path("expect").path("codes")) {
                String key = expected.get("code").asText() + "@" + expected.get("propertyKey").asText();
                assertTrue(actual.contains(key), () -> id + " missing " + key + " in " + actual);
            }
            if (valid) {
                var register = result.checkedStructure().orElseThrow().register();
                assertNotNull(register, id);
                assertFalse(register.dimensions().isEmpty(), id);
            }
            checked++;
        }
        assertEquals(34, checked);
    }

    @Test
    void renamingAFieldKeepsItsRoleReference() throws Exception {
        ObjectNode tree = (ObjectNode) mapper.readTree(Files.readString(contracts.resolve("register-draft-day.json")));
        String roleId = tree.path("register").path("dimensions").get(0).asText();
        ((ObjectNode) tree.withArray("fields").get(0)).put("name", "itemRef");
        var draft = codec.decode(tree.toString());
        var result = validator.validate(draft, context());
        assertFalse(result.hasErrors(), () -> result.problems().toString());
        assertEquals(roleId, draft.register().dimensions().getFirst().toString());
        assertEquals("itemRef", result.checkedStructure().orElseThrow().register().dimensions().getFirst().name());
        assertEquals("item_ref_id", result.checkedStructure().orElseThrow().register().dimensions().getFirst().column());
    }

    @Test
    void envelopeV3RejectsForeignSectionsAndRecipes() throws Exception {
        String none = Files.readString(contracts.resolve("register-draft-none.json"));
        ObjectNode foreignRecipe = (ObjectNode) mapper.readTree(none);
        ((ObjectNode) foreignRecipe.path("recipe")).put("id", "standard-catalog-v2").put("version", 2);
        assertThrows(DraftJsonException.class, () -> codec.decode(foreignRecipe.toString()));

        ObjectNode withOwner = (ObjectNode) mapper.readTree(none);
        withOwner.withObject("owner").put("targetTypeId", "com.example.Target").put("fieldName", "owner")
            .withObject("columnName").put("mode", "AUTO").put("text", "");
        withOwner.withObject("owner").put("label", "Владелец");
        assertThrows(DraftJsonException.class, () -> codec.decode(withOwner.toString()));

        ObjectNode withUnknownRegisterProperty = (ObjectNode) mapper.readTree(none);
        withUnknownRegisterProperty.withObject("register").put("future", true);
        assertThrows(DraftJsonException.class, () -> codec.decode(withUnknownRegisterProperty.toString()));

        var draft = codec.decode(none);
        var withRegisterOnV1 = new EntityDraft(1, RecipeCatalog.STANDARD_CATALOG, draft.draftId(), draft.entity(),
            draft.fields(), draft.sections(), null, draft.register());
        assertThrows(DraftJsonException.class, () -> codec.encode(withRegisterOnV1));
        var v3WithoutRegister = new EntityDraft(3, RecipeCatalog.INDEPENDENT_REGISTER, draft.draftId(), draft.entity(),
            draft.fields(), draft.sections());
        assertThrows(DraftJsonException.class, () -> codec.encode(v3WithoutRegister));
    }

    @Test
    void registerDraftCreationStartsInEnvelopeV3() {
        var draft = new DraftOperations().createRegister(RegisterDraft.DAY);
        assertEquals(3, draft.schemaVersion());
        assertEquals(RecipeCatalog.INDEPENDENT_REGISTER, draft.recipe());
        assertEquals(RegisterDraft.DAY, draft.register().period());
        assertEquals(RegisterDraft.NAMED_AND_CANONICAL_CRUD, draft.register().writeProfile());
        assertTrue(draft.sections().isEmpty());
        assertEquals(draft, codec.decode(codec.encode(draft)));
        var result = validator.validate(draft, context());
        assertTrue(result.problems().stream().anyMatch(p -> p.code() == DraftProblem.Code.REGISTER_ROLE_MISSING));
    }

    private ProjectDesignContext context() {
        var targets = new HashMap<String, ReferenceTargetDescriptor>();
        targets.put("org.ip.model.Nomenclature", reference("org.ip.model.Nomenclature", "STANDARD_ROOT",
            ReferenceTargetDescriptor.Fact.YES, ReferenceTargetDescriptor.Fact.YES,
            ReferenceTargetDescriptor.Eligibility.SUPPORTED));
        targets.put("org.ip.model.UnsupportedTarget", reference("org.ip.model.UnsupportedTarget", "NESTED",
            ReferenceTargetDescriptor.Fact.YES, ReferenceTargetDescriptor.Fact.NO,
            ReferenceTargetDescriptor.Eligibility.UNSUPPORTED));
        targets.put("org.ip.model.UnknownFacts", reference("org.ip.model.UnknownFacts", "UNKNOWN",
            ReferenceTargetDescriptor.Fact.UNKNOWN, ReferenceTargetDescriptor.Fact.UNKNOWN,
            ReferenceTargetDescriptor.Eligibility.UNKNOWN));
        targets.put("org.ip.model.InfoRegisterTarget", reference("org.ip.model.InfoRegisterTarget", "STANDARD_ROOT",
            ReferenceTargetDescriptor.Fact.YES, ReferenceTargetDescriptor.Fact.YES,
            ReferenceTargetDescriptor.Eligibility.SUPPORTED));
        var types = new HashMap<String, RuntimeDesignSnapshot.TypeFacts>();
        types.put("org.ip.model.InfoRegisterTarget", typeFacts("org.ip.model.InfoRegisterTarget", "INFORMATION_REGISTER"));
        types.put("org.ip.model.Nomenclature", typeFacts("org.ip.model.Nomenclature", "CATALOG"));
        var snapshot = new RuntimeDesignSnapshot("r1-register", true, types,
            Map.of("REGISTER_FACTS", new RuntimeDesignSnapshot.Prerequisite(
                RuntimeDesignSnapshot.Status.AVAILABLE, "Validated RegisterRegistry: 2 register declaration(s)")));
        return new ProjectDesignContext("register-g1", "1.0", "com.example.registerconsumer",
            ProjectDesignContext.Names.known(Set.of()), ProjectDesignContext.Names.known(Set.of("existing_table")),
            ProjectDesignContext.Names.known(Set.of()), ProjectDesignContext.Names.known(Set.of("MONEY")),
            targets, true, snapshot);
    }

    private static ReferenceTargetDescriptor reference(String typeId, String exposure,
            ReferenceTargetDescriptor.Fact representation, ReferenceTargetDescriptor.Fact selection,
            ReferenceTargetDescriptor.Eligibility eligibility) {
        return new ReferenceTargetDescriptor(typeId, typeId, exposure, "java.lang.Long",
            representation, selection, eligibility, "");
    }

    private static RuntimeDesignSnapshot.TypeFacts typeFacts(String typeId, String kind) {
        return new RuntimeDesignSnapshot.TypeFacts(typeId, kind, "DERIVED", "src", "STANDARD_ROOT",
            "PLATFORM_DEFAULT", "", "DERIVED", "", Set.of(), Set.of(), false, false, "",
            List.of(), List.of(), List.of(), List.of(), List.of(), "", List.of());
    }

    private static void apply(ObjectNode root, JsonNode edit) {
        String op = edit.get("op").asText(), path = edit.get("path").asText();
        String[] parts = path.substring(1).split("/");
        JsonNode parent = root;
        for (int i = 0; i < parts.length - 1; i++)
            parent = parent.isArray() ? parent.get(Integer.parseInt(parts[i])) : parent.get(parts[i]);
        String last = parts[parts.length - 1];
        if (parent.isArray()) {
            ArrayNode array = (ArrayNode) parent;
            if (op.equals("add")) {
                if (last.equals("-")) array.add(edit.get("value"));
                else array.insert(Integer.parseInt(last), edit.get("value"));
            } else if (op.equals("remove")) array.remove(Integer.parseInt(last));
            else array.set(Integer.parseInt(last), edit.get("value"));
        } else {
            ObjectNode object = (ObjectNode) parent;
            if (op.equals("remove")) object.remove(last);
            else object.set(last, edit.get("value"));
        }
    }
}
