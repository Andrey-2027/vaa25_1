package org.ipro.design;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.ipro.design.Fixtures.*;

class RecipeContractTest {
    @Test void extensionInheritedContractsEnableOnlyTheExactDocumentRecipe() {
        for (var ref : List.of(RecipeCatalog.STANDARD_CATALOG, RecipeCatalog.STANDARD_CATALOG_V2)) {
            assertEquals(RecipeCatalog.inheritedFields(), RecipeCatalog.inheritedFields(ref));
            assertEquals(List.of("code", "name"), RecipeCatalog.defaultPublications(ref));
            assertEquals(RecipeCatalog.BASE_TYPE, RecipeCatalog.baseType(ref));
        }
        var document = RecipeCatalog.inheritedFields(RecipeCatalog.STANDARD_DOCUMENT);
        assertEquals(List.of("number", "date"), RecipeCatalog.defaultPublications(RecipeCatalog.STANDARD_DOCUMENT));
        assertEquals("org.ipro.crud.StandardDocumentEntity", RecipeCatalog.baseType(RecipeCatalog.STANDARD_DOCUMENT));
        assertEquals("DOCUMENT_NUMBER", document.getFirst().numberingRole());
        assertEquals(50, document.getFirst().length());
        assertEquals(RecipeCatalog.FieldType.DATE, document.getLast().type());
        assertTrue(document.stream().allMatch(RecipeCatalog.InheritedField::serverRequired));
        assertTrue(RecipeCatalog.supportsDraft(RecipeCatalog.STANDARD_DOCUMENT));
        assertEquals(RecipeCatalog.STANDARD_DOCUMENT, new DraftOperations().create(RecipeCatalog.STANDARD_DOCUMENT).recipe());
        assertThrows(IllegalArgumentException.class, () -> RecipeCatalog.inheritedFields(new RecipeRef("custom-plain-v1", 1)));
    }
    @Test void executableCatalogMatchesVersionedResource() throws Exception {
        try (var stream = getClass().getResourceAsStream("/org/ipro/design/recipes/standard-catalog-v1.json")) {
            var recipe = JSON.readTree(stream);
            assertEquals(RecipeCatalog.STANDARD_CATALOG.id(), recipe.path("id").asText());
            assertEquals(RecipeCatalog.STANDARD_CATALOG.version(), recipe.path("version").asInt());
            assertEquals(RecipeCatalog.BASE_TYPE, recipe.path("baseType").asText());
            assertEquals(RecipeCatalog.generationEnabled(), recipe.path("generationEnabled").asBoolean());
            assertEquals(JSON.valueToTree(RecipeCatalog.generationPrerequisites()), recipe.path("generationPrerequisites"));
            assertEquals(JSON.valueToTree(RecipeCatalog.inheritedFields()), recipe.path("inheritedFields"));
            var names = new HashSet<String>(); recipe.path("systemFieldNames").forEach(n -> names.add(n.asText()));
            assertEquals(RecipeCatalog.systemFieldNames(), names);
            assertEquals(JSON.valueToTree(RecipeCatalog.FieldType.values()), recipe.path("fieldTypes"));
            assertEquals(RecipeCatalog.MAX_FIELDS, recipe.path("importLimits").path("totalFields").asInt());
            assertEquals(RecipeCatalog.MAX_SECTIONS, recipe.path("importLimits").path("sections").asInt());
            assertEquals(RecipeCatalog.MAX_TEXT, recipe.path("importLimits").path("textCharacters").asInt());
            assertEquals(RecipeCatalog.initialConstraints("STRING").length(), recipe.path("suggestions").path("stringLength").asText());
            assertEquals(RecipeCatalog.initialConstraints("DECIMAL").precision(), recipe.path("suggestions").path("decimalPrecision").asText());
            assertEquals(RecipeCatalog.initialConstraints("DECIMAL").scale(), recipe.path("suggestions").path("decimalScale").asText());
            var section = valid().sections().getFirst();
            assertEquals(DraftSuggestions.parentField(section), recipe.path("suggestions").path("parentFieldName").asText());
            assertEquals(new DraftOperations().create().entity().icon(), recipe.path("suggestions").path("icon").asText());
        }
    }
}
