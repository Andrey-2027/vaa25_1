package org.ipro.design.json;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.ipro.design.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DraftJsonCodecTest {
    final DraftJsonCodec codec = new DraftJsonCodec();
    final ObjectMapper mapper = new ObjectMapper();
    @Test void referenceInputRoundTripsForBothRecipes() {
        for(var recipe:List.of(RecipeCatalog.STANDARD_CATALOG_V2,RecipeCatalog.STANDARD_DOCUMENT)) {
            var draft=new DraftOperations().create(recipe);
            var field=new FieldDraft(UUID.randomUUID(),"target","Ссылка","REFERENCE",DraftText.explicit("target_id"),true,FieldDraft.UiRequired.REQUIRED,ConstraintDraft.empty(),new ReferenceDraft("example.ExistingTarget"),FieldApplications.root());
            draft=draft.withFields(List.of(field));assertEquals(draft,codec.decode(codec.encode(draft)));
        }
    }
    @Test void documentV1EnvelopePreservesUnfinishedInputWithoutUpgradingOtherRecipes() throws Exception {
        var original = codec.decode(fixture("semantic-errors"));
        var document = new EntityDraft(1, RecipeCatalog.STANDARD_DOCUMENT, original.draftId(), original.entity(), original.fields(), original.sections());
        var bytes = codec.encode(document);
        assertEquals(document, codec.decode(bytes));
        assertArrayEquals(bytes, codec.encode(codec.decode(bytes)));
        var tree = (ObjectNode) mapper.readTree(bytes);
        tree.withObject("recipe").put("version", 2); reject(tree);
        tree.withObject("recipe").put("version", 1);
        tree.putNull("owner"); reject(tree);
        assertEquals(RecipeCatalog.STANDARD_CATALOG, codec.decode(codec.encode(original)).recipe());
    }
    String fixture(String name) throws IOException { return Files.readString(Path.of("../entity-design-core/src/test/resources/org/ipro/design/fixtures/" + name + ".json")); }
    ObjectNode tree() throws IOException { return (ObjectNode)mapper.readTree(fixture("catalog-two-sections")); }
    void reject(JsonNode tree) { assertThrows(DraftJsonException.class, () -> codec.decode(tree.toString())); }

    @Test void existingEntityMarkerIsNeitherImportedNorExported() throws Exception {
        var marked=tree(); ((ObjectNode)marked.path("recipe")).put("id",RecipeCatalog.EXISTING_ENTITY.id()).put("version",RecipeCatalog.EXISTING_ENTITY.version()); reject(marked);
        var draft=codec.decode(fixture("catalog-two-sections"));
        var existing=new EntityDraft(2,RecipeCatalog.EXISTING_ENTITY,draft.draftId(),draft.entity(),draft.fields(),List.of());
        assertThrows(RuntimeException.class,() -> codec.encode(existing));
    }
    @Test void explicitlyVersionedRecipeUsesTheSameEnvelopeWithoutReinterpretingOldDocuments() throws Exception {
        var original=codec.decode(fixture("catalog-two-sections"));
        var updated=new EntityDraft(1,RecipeCatalog.STANDARD_CATALOG_V2,original.draftId(),original.entity(),original.fields(),original.sections());
        assertEquals(updated,codec.decode(codec.encode(updated)));
        assertEquals(RecipeCatalog.STANDARD_CATALOG,codec.decode(codec.encode(original)).recipe());
        assertEquals(original.draftId(),updated.draftId()); assertEquals(original.fields(),updated.fields());
        var wrong=tree(); ((ObjectNode)wrong.path("recipe")).put("version",2); reject(wrong);
        ((ObjectNode)wrong.path("recipe")).put("id",RecipeCatalog.STANDARD_CATALOG_V2.id()).put("version",1); reject(wrong);
        ((ObjectNode)wrong.path("recipe")).put("version",3); reject(wrong);
        for (var recipe : RecipeCatalog.recipes()) if (!recipe.draftSupported()) {
            var unsupported=tree(); ((ObjectNode)unsupported.path("recipe")).put("id",recipe.ref().id()).put("version",recipe.ref().version()); reject(unsupported);
        }
    }

    @Test void fixturesRoundTripWithoutNewIdsCachedFactsOrSemanticRepair() throws Exception {
        for (String name : List.of("unfinished", "catalog-two-sections", "semantic-errors")) {
            String input = fixture(name); EntityDraft draft = codec.decode(input);
            byte[] exported = codec.encode(draft);
            assertEquals(draft, codec.decode(exported));
            assertEquals(mapper.readTree(input), mapper.readTree(exported));
            assertArrayEquals(exported, codec.encode(draft));
        }
    }
    @Test void unknownFieldTypeRawTextAndUnicodeRemainLiteral() throws Exception {
        var root = tree(); var field = (ObjectNode)root.withArray("fields").get(0);
        field.put("type", "FUTURE_TYPE"); field.withObject("constraints").put("length", "12x");
        root.withObject("entity").put("label", "Материал 🌿\n\"текст\"");
        var draft = codec.decode(root.toString());
        assertEquals("FUTURE_TYPE", draft.fields().getFirst().type());
        assertEquals("12x", draft.fields().getFirst().constraints().length());
        assertEquals(draft, codec.decode(codec.encode(draft)));
    }
    @Test void rejectsWrongVersionsMissingUnknownAndCoercedProperties() throws Exception {
        var mutations = List.<java.util.function.Consumer<ObjectNode>>of(
            n -> n.put("schemaVersion", 2), n -> n.put("schemaVersion", "1"),
            n -> n.withObject("recipe").put("version", 2), n -> n.withObject("recipe").put("id", "other"),
            n -> n.remove("fields"), n -> n.put("@class", "java.lang.Runtime"),
            n -> n.withObject("entity").put("future", true), n -> n.withObject("entity").putNull("className"),
            n -> ((ObjectNode)n.withArray("fields").get(0)).put("serverRequired", "false"),
            n -> ((ObjectNode)n.withArray("fields").get(0)).put("uiRequired", "OTHER"),
            n -> n.withObject("entity").withObject("packageName").put("mode", "AUTO").put("text", "stale-auto"));
        for (var mutation : mutations) { var n = tree(); mutation.accept(n); reject(n); }
    }
    @Test void rejectsMalformedTrailingDuplicateKeysAndInvalidUtf8() throws Exception {
        String valid = fixture("unfinished");
        for (String text : List.of("", "{", valid + " {}", valid.replace("\"schemaVersion\": 1", "\"schemaVersion\":1,\"schemaVersion\":1"), "{\"x\":1,\"x\":2}")) {
            assertThrows(DraftJsonException.class, () -> codec.decode(text));
        }
        assertThrows(DraftJsonException.class, () -> codec.decode(new byte[]{(byte)0xc3, (byte)0x28}));
        assertThrows(DraftJsonException.class, () -> codec.decode("\ud800"));
        var n = tree(); n.withObject("entity").put("label", "\ud800"); reject(n);
    }
    @Test void idsAreCanonicalAndGloballyUniqueIncludingDraftAndRows() throws Exception {
        var n = tree(); n.put("draftId", "1-1-1-1-1"); reject(n);
        n = tree(); n.put("draftId", "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"); reject(n);
        n = tree(); ((ObjectNode)n.withArray("sections").get(0)).put("id", n.path("fields").get(0).path("id").asText()); reject(n);
        n = tree(); ((ObjectNode)n.withArray("fields").get(0)).put("id", n.path("draftId").asText()); reject(n);
    }
    @Test void totalFieldsAndSectionsAreBoundedAcrossContainers() throws Exception {
        var n = tree(); var fields = n.withArray("fields"); var template = fields.get(0).deepCopy(); fields.removeAll();
        n.withArray("sections").removeAll();
        for (int i=0; i<1000; i++) { var f=(ObjectNode)template.deepCopy(); f.put("id", UUID.randomUUID().toString()); fields.add(f); }
        assertEquals(1000, codec.decode(n.toString()).fields().size());
        var section = (ObjectNode)tree().withArray("sections").get(0).deepCopy();
        var child=(ObjectNode)template.deepCopy(); child.put("id", UUID.randomUUID().toString()); section.withArray("fields").removeAll().add(child);
        n.withArray("sections").add(section); reject(n);
        n = tree(); var sections=n.withArray("sections"); section=(ObjectNode)sections.get(0).deepCopy(); section.withArray("fields").removeAll(); sections.removeAll();
        for(int i=0;i<51;i++) { var s=section.deepCopy(); s.put("id",UUID.randomUUID().toString()); sections.add(s); }
        reject(n);
    }
    @Test void boundsBytesDepthTextAndStreamBeforeParsing() throws Exception {
        assertThrows(DraftJsonException.class, () -> codec.decode(new byte[DraftJsonCodec.MAX_BYTES+1]));
        String nested = "[".repeat(33) + "0" + "]".repeat(33);
        assertThrows(DraftJsonException.class, () -> codec.decode(nested));
        var n=tree(); n.withObject("entity").put("label", "🌿".repeat(4096));
        assertEquals(8192, codec.decode(n.toString()).entity().label().length());
        n.withObject("entity").put("label", "a".repeat(4097)); reject(n);
        var source = new ByteArrayInputStream(new byte[DraftJsonCodec.MAX_BYTES+100]);
        assertThrows(DraftJsonException.class, () -> codec.decode(source));
        assertEquals(99, source.available());
    }
    @Test void exportRefusesStructurallyInvalidDocumentsButAllowsSemanticErrors() throws Exception {
        var valid=codec.decode(fixture("semantic-errors")); assertNotNull(codec.encode(valid));
        var bad=new EntityDraft(99,valid.recipe(),valid.draftId(),valid.entity(),valid.fields(),valid.sections());
        assertThrows(DraftJsonException.class, () -> codec.encode(bad));
    }
}
