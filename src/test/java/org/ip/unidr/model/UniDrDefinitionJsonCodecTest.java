package org.ipro.unidr.model;
import org.ip.unidr.model.UniDrDefinitions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.ipro.unidr.model.UniDrValidationException.Code;
import org.ipro.unidr.source.UniDrSourceCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;

class UniDrDefinitionJsonCodecTest {
    private final UniDrDefinitionJsonCodec codec = new UniDrDefinitionJsonCodec(new UniDrDefinitionValidator(UniDrDefinitions.catalog()));
    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest @ValueSource(strings = {"", " ", "{", "[]", "null", "{\"schemaVersion\":1}"})
    void malformedOrIncompleteInputDoesNotBecomeADefaultRecipe(String text) {
        assertRejected(text, Code.INVALID_JSON);
    }

    @Test void unknownRecipeVersionGetsAnExplicitDiagnosticEvenWithDifferentStructure() {
        assertRejected("{\"schemaVersion\":3,\"futureLayout\":{}}", Code.UNSUPPORTED_SCHEMA);
    }

    @Test void unknownSourceVersionAndUnknownFieldSurviveStructuralParsingAsModelErrors() throws Exception {
        assertRejected(edited(tree -> tree.put("sourceVersion", 2)), Code.INCOMPATIBLE_SOURCE);
        assertRejected(edited(tree -> ((ObjectNode) tree.withArray("columns").get(0)).put("fieldId", "amount")), Code.UNKNOWN_FIELD);
    }

    @ParameterizedTest @ValueSource(strings = {"jpql", "owner", "lastRunValues", "@class"})
    void extraRootPropertiesAreRejectedRatherThanDiscarded(String key) throws Exception {
        assertRejected(edited(tree -> tree.put(key, "untrusted")), Code.INVALID_JSON);
    }

    @Test void extraNestedPropertiesAndJavaTypeDiscriminatorsAreRejected() throws Exception {
        assertRejected(edited(tree -> ((ObjectNode) tree.withArray("columns").get(0)).put("expression", "n.price")), Code.INVALID_JSON);
        assertRejected(edited(tree -> tree.withArray("compatibilityFilters").addObject().put("fieldId", "quantity").put("operator", "EQ")
            .putObject("value").put("kind", "java.lang.ProcessBuilder")), Code.INVALID_JSON);
    }

    @Test void duplicateKeysTrailingTokensAndScalarCoercionsAreRejected() throws Exception {
        String valid = codec.encode(UniDrDefinitions.receivingItemsSummary());
        assertRejected(valid + " {}", Code.INVALID_JSON);
        assertRejected(valid.replace("\"schemaVersion\":2", "\"schemaVersion\":2,\"schemaVersion\":2"), Code.INVALID_JSON);
        assertRejected(edited(tree -> tree.put("sourceVersion", "1")), Code.INVALID_JSON);
        assertRejected(edited(tree -> tree.put("sourceVersion", 1.5)), Code.INVALID_JSON);
        assertRejected(edited(tree -> tree.put("grandTotal", "true")), Code.INVALID_JSON);
        assertRejected(edited(tree -> tree.remove("grandTotal")), Code.INVALID_JSON);
        assertRejected(edited(tree -> tree.putNull("sourceVersion")), Code.INVALID_JSON);
        assertRejected(edited(tree -> ((ObjectNode) tree.withArray("columns").get(0)).put("fieldId", 123)), Code.INVALID_JSON);
    }

    @Test void numericLiteralMustBeAStringAndEnumsMustBeKnown() throws Exception {
        assertRejected(edited(tree -> {
            var filter = tree.withArray("compatibilityFilters").addObject().put("fieldId", "quantity").put("operator", "GE");
            filter.putObject("value").put("kind", "LITERAL").put("type", "DECIMAL").putArray("values").add(1.25);
        }), Code.INVALID_JSON);
        assertRejected(edited(tree -> ((ObjectNode) tree.withArray("measures").get(0)).put("aggregation", "MEDIAN")), Code.INVALID_JSON);
    }

    @Test void nullListsAndNullElementsAreRejected() throws Exception {
        assertRejected(edited(tree -> tree.putNull("groups")), Code.INVALID_JSON);
        assertRejected(edited(tree -> tree.withArray("columns").addNull()), Code.INVALID_JSON);
    }
    @Test void fieldPresentationRoundTripsAndInvalidFormatsWidthsAndUnknownFieldsAreRejected() {
        var base=UniDrDefinitions.receivingItemsSummary();
        var valid=base.withFieldStyles(java.util.Map.of("quantity",new UniDrDefinition.FieldStyle(220,UniDrDefinition.Alignment.CENTER,"#,##0.000"),"date",new UniDrDefinition.FieldStyle(null,null,"yyyy-MM-dd")));
        assertThat(codec.decode(codec.encode(valid))).isEqualTo(valid);
        for(var style:java.util.List.of(new UniDrDefinition.FieldStyle(0,null,null),new UniDrDefinition.FieldStyle(2001,null,null),new UniDrDefinition.FieldStyle(null,null,"0.0.0")))
            assertThatThrownBy(()->codec.encode(base.withFieldStyles(java.util.Map.of("quantity",style)))).isInstanceOf(UniDrValidationException.class);
        assertThatThrownBy(()->codec.encode(base.withFieldStyles(java.util.Map.of("date",new UniDrDefinition.FieldStyle(null,null,"HH:mm"))))).isInstanceOf(UniDrValidationException.class);
        assertThatThrownBy(()->codec.encode(base.withFieldStyles(java.util.Map.of("document",new UniDrDefinition.FieldStyle(null,null,"0.00"))))).isInstanceOf(UniDrValidationException.class);
        assertThatThrownBy(()->codec.encode(base.withFieldStyles(java.util.Map.of("unknown",UniDrDefinition.FieldStyle.defaults())))).isInstanceOf(UniDrValidationException.class);
    }

    @Test void oversizedAndDeepInputAreBoundedAndDoNotEchoInput() {
        assertRejected(" ".repeat(UniDrDefinitionJsonCodec.MAX_JSON_CHARACTERS + 1), Code.INVALID_JSON);
        String privateText = "private-input-do-not-echo";
        String deep = "{\"schemaVersion\":1,\"grandTotal\":true,\"extra\":" + "[".repeat(40) + "\"" + privateText + "\"" + "]".repeat(40) + "}";
        assertThatThrownBy(() -> codec.decode(deep)).isInstanceOf(UniDrValidationException.class).hasMessageNotContaining(privateText);
    }

    @Test void printBindingRoundTripsAndStaysOptional() throws Exception {
        var base=UniDrDefinitions.receivingItemsSummary();
        var bound=base.withTargetEntityClass("org.ip.domain.ReceivingDocument");
        assertThat(codec.decode(codec.encode(bound))).isEqualTo(bound);
        assertThat(codec.decode(edited(tree->tree.remove("targetEntityClass"))).targetEntityClass()).isNull();
        assertThat(base.withTargetEntityClass("  ").targetEntityClass()).isNull();
        assertRejected(edited(tree->tree.put("targetEntityClass",42)),Code.INVALID_JSON);
        assertRejected(edited(tree->tree.put("targetEntityClass","org..Bad")),Code.INVALID_JSON);
    }

    @Test void computedColumnsBordersAndNumbersRoundTripAndLegacyRecipesKeepDefaults() throws Exception {
        var base=UniDrDefinitions.receivingItemsSummary();
        var recipe=base.withComputedColumns(java.util.List.of(new UniDrDefinition.ComputedColumn("calc_amount","Стоимость",UniDrDefinition.Computation.FORMULA,"{quantity} * 10")))
            .withRowNumbers(true).withFieldStyles(java.util.Map.of("calc_amount",new UniDrDefinition.FieldStyle(220,null,"0.00",false)));
        assertThat(codec.decode(codec.encode(recipe))).isEqualTo(recipe);
        assertThat(recipe.withQuery(null).withFieldStyles(recipe.fieldStyles())).isEqualTo(recipe);
        assertThat(codec.decode(edited(tree->{tree.remove("computedColumns");tree.remove("rowNumbers");}))).isEqualTo(base);
        assertRejected(edited(tree->tree.put("rowNumbers","true")),Code.INVALID_JSON);
    }
    @Test void brokenComputedReferencesTypesGrammarAndCollisionsAreRejected() {
        var base=UniDrDefinitions.receivingItemsSummary();
        for(String expression:java.util.List.of("{missing} * 2","{nomenclature} * 2","{quantity} *","System.exit(0)","(".repeat(1001)))
            assertThatThrownBy(()->codec.encode(base.withComputedColumns(java.util.List.of(new UniDrDefinition.ComputedColumn("calc_amount","Стоимость",UniDrDefinition.Computation.FORMULA,expression)))))
                .isInstanceOf(UniDrValidationException.class);
        for(String expression:java.util.List.of("{missing}","{quantity","quantity}","{}"))
            assertThatThrownBy(()->codec.encode(base.withComputedColumns(java.util.List.of(new UniDrDefinition.ComputedColumn("calc_text","Описание",UniDrDefinition.Computation.TEXT,expression)))))
                .isInstanceOf(UniDrValidationException.class);
        var computed=new UniDrDefinition.ComputedColumn("calc_a","A",UniDrDefinition.Computation.FORMULA,"1");
        assertThatThrownBy(()->codec.encode(base.withComputedColumns(java.util.List.of(computed,computed)))).isInstanceOf(UniDrValidationException.class);
        assertThatThrownBy(()->codec.encode(base.withComputedColumns(java.util.List.of(new UniDrDefinition.ComputedColumn("quantity","A",UniDrDefinition.Computation.FORMULA,"1"))))).isInstanceOf(UniDrValidationException.class);
    }
    @Test void computedQuantityAggregateRequiresUnitEvenWithoutDirectQuantityMeasure() {
        var base=UniDrDefinitions.receivingItemsSummary();
        var recipe=new UniDrDefinition(1,base.sourceId(),1,"Стоимость",java.util.List.of(),
            java.util.List.of(new UniDrDefinition.Measure("calc_amount",UniDrDefinition.Aggregation.SUM,null)),java.util.List.of(),java.util.List.of(),base.parameters(),java.util.List.of(),UniDrDefinition.Mode.SUMMARY,true)
            .withComputedColumns(java.util.List.of(new UniDrDefinition.ComputedColumn("calc_amount","Стоимость",UniDrDefinition.Computation.FORMULA,"{quantity} * 10")));
        assertThat(new UniDrDefinitionValidator(UniDrDefinitions.catalog()).parameterRequired(recipe,"unit")).isTrue();
    }

    private String edited(Consumer<ObjectNode> edit) throws Exception {
        ObjectNode tree = (ObjectNode) json.readTree(codec.encode(UniDrDefinitions.receivingItemsSummary()));
        edit.accept(tree); return json.writeValueAsString(tree);
    }
    private void assertRejected(String text, Code code) {
        assertThatThrownBy(() -> codec.decode(text)).isInstanceOfSatisfying(UniDrValidationException.class,
            invalid -> assertThat(invalid.issues()).anyMatch(issue -> issue.code() == code));
    }
}
