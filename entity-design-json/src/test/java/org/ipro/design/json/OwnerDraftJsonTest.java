package org.ipro.design.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.ipro.design.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OwnerDraftJsonTest {
    private static final List<RecipeRef> RECIPES = List.of(
        RecipeCatalog.STANDARD_CATALOG_V2, RecipeCatalog.STANDARD_CATALOG_V3, RecipeCatalog.STANDARD_CATALOG_V4,
        RecipeCatalog.STANDARD_DOCUMENT, RecipeCatalog.STANDARD_DOCUMENT_V2, RecipeCatalog.STANDARD_DOCUMENT_V3);
    private final DraftJsonCodec codec = new DraftJsonCodec();
    private final ObjectMapper mapper = new ObjectMapper();
    private EntityDraft draft(RecipeRef recipe, OwnerDraft owner) {
        var d = new DraftOperations().create(recipe);
        return new EntityDraft(2, recipe, d.draftId(), d.entity(), d.fields(), d.sections(), owner);
    }
    private OwnerDraft owner() {
        return new OwnerDraft(" missing.Target ", "bad owner", DraftText.explicit(" RAW COLUMN "), " Подпись 🌿\n\"текст\" ");
    }
    private ObjectNode tree(EntityDraft draft) throws Exception { return (ObjectNode)mapper.readTree(codec.encode(draft)); }
    private void reject(ObjectNode tree) { assertThrows(DraftJsonException.class, () -> codec.decode(tree.toString())); }

    @Test void ownerV2PreservesRawInvalidInputIdsAndModesAcrossRecipes() {
        for (var recipe : RECIPES) {
            var d = draft(recipe, owner()); var bytes = codec.encode(d); var decoded = codec.decode(bytes);
            assertEquals(d, decoded); assertArrayEquals(bytes, codec.encode(decoded));
            assertEquals(" RAW COLUMN ", decoded.owner().columnName().text());
            assertEquals(" missing.Target ", decoded.owner().targetTypeId());
        }
    }
    @Test void explicitNullIsRequiredInV2AndForbiddenInV1() throws Exception {
        for (var recipe : RECIPES) {
            var d = draft(recipe, null); var tree = tree(d);
            assertTrue(tree.has("owner")); assertTrue(tree.get("owner").isNull()); assertEquals(d, codec.decode(codec.encode(d)));
            tree.remove("owner"); reject(tree);
            var old = new DraftOperations().create(recipe); var oldTree = tree(old);
            assertFalse(oldTree.has("owner")); oldTree.putNull("owner"); reject(oldTree);
            assertThrows(DraftJsonException.class, () -> codec.encode(old.withOwner(owner())));
        }
    }
    @Test void ownerShapeIsStrictAndAutoIntentDoesNotAcceptStoredSuggestions() throws Exception {
        var original = tree(draft(RecipeCatalog.STANDARD_CATALOG_V2, owner()));
        for (var key : List.of("targetTypeId", "fieldName", "columnName", "label")) {
            var missing = original.deepCopy(); ((ObjectNode)missing.get("owner")).remove(key); reject(missing);
            var wrong = original.deepCopy(); ((ObjectNode)wrong.get("owner")).putNull(key); reject(wrong);
        }
        var unknown = original.deepCopy(); ((ObjectNode)unknown.get("owner")).put("required", true); reject(unknown);
        var auto = original.deepCopy(); ((ObjectNode)auto.at("/owner/columnName")).put("mode", "AUTO"); reject(auto);
        ((ObjectNode)auto.at("/owner/columnName")).put("text", "");
        assertEquals(DraftText.auto(), codec.decode(auto.toString()).owner().columnName());
        var longText = original.deepCopy(); ((ObjectNode)longText.get("owner")).put("label", "x".repeat(4097)); reject(longText);
    }
    @Test void duplicateOwnerKeysAndUnknownEnvelopeVersionsAreRejected() throws Exception {
        var d = draft(RecipeCatalog.STANDARD_CATALOG_V2, owner());
        var json = new String(codec.encode(d), java.nio.charset.StandardCharsets.UTF_8);
        assertThrows(DraftJsonException.class, () -> codec.decode(json.replace("\"targetTypeId\" :", "\"targetTypeId\" : \"other\", \"targetTypeId\" :")));
        var tree = tree(d); tree.put("schemaVersion", 3); reject(tree);
        tree.put("schemaVersion", 2); tree.withObject("recipe").put("id", "standard-catalog-v1").put("version", 1); reject(tree);
    }
    @Test void documentOwnerIsExportableButSemanticValidatorRejectsIt() {
        var d = codec.decode(codec.encode(draft(RecipeCatalog.STANDARD_DOCUMENT, owner())));
        var names = ProjectDesignContext.Names.known(Set.of());
        var context = new ProjectDesignContext("p", "v", "example", names, names, names, names, Map.of(), false);
        var validation = new DraftValidator().validate(d, context);
        assertTrue(validation.problems().stream().anyMatch(p -> p.code() == DraftProblem.Code.OWNER_UNSUPPORTED_RECIPE
                && p.target().propertyKey().equals("owner") && p.target().fieldId() == null));
        assertTrue(validation.checkedStructure().isEmpty());
    }
}
