package org.ip.unidr.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.ip.unidr.model.UniDrDefinition.*;
import org.ip.unidr.model.UniDrParameter.*;
import org.ip.unidr.query.UniDrQuerySourceService;
import org.ip.unidr.source.UniDrSourceCatalog;
import org.ip.unidr.store.UniDrFileStore;
import org.ip.unidr.run.UniDrAccess;
import org.ipro.filtergrid.filter.*;
import org.ipro.reportstudio.data.QueryField;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class UniDrDefinitionV2Test {
    @TempDir Path directory;
    final ObjectMapper json=new ObjectMapper();
    final UniDrDefinitionJsonCodec codec=new UniDrDefinitionJsonCodec(new UniDrDefinitionValidator(UniDrSourceCatalog.pilot()));
    @BeforeEach void authenticate(){org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("alice",null,List.of()));}
    @AfterEach void clearAuthentication(){org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    @Test void openingLegacyFileDoesNotWriteAndSavingKeepsIdentityAndFileVersion()throws Exception {
        var old=Files.readString(Path.of("examples/unidr/receiving-items-v1.json"));
        var document=json.createObjectNode();document.put("fileVersion",1);document.put("id",42L);document.put("author","alice");document.put("description","Пример v1");document.set("definition",json.readTree(old));
        var path=directory.resolve("42.json");Files.writeString(path,document.toString());var before=Files.readAllBytes(path);
        var store=new UniDrFileStore(directory,codec,new UniDrAccess(()->true,()->"alice"));var loaded=store.load(42L);
        assertThat(loaded.definition().schemaVersion()).isEqualTo(2);assertThat(Files.readAllBytes(path)).isEqualTo(before);
        var saved=store.update(42L,loaded.revision(),loaded.definition().withTitle("Сохранённый v2"),loaded.description());
        var written=json.readTree(Files.readString(path));assertThat(written.path("fileVersion").asInt()).isEqualTo(1);assertThat(written.path("definition").path("schemaVersion").asInt()).isEqualTo(2);
        assertThat(saved.id()).isEqualTo(42L);assertThat(saved.author()).isEqualTo("alice");
        assertThatThrownBy(()->store.update(42L,loaded.revision(),loaded.definition(),"")).hasMessageContaining("другой вкладке");
    }
    @Test void canonicalParametersAndStandardTreeSurviveEveryCopyOperation()throws Exception {
        var base=UniDrDefinitions.receivingItemsSummary();var declarations=new ArrayList<>(base.parameterDeclarations());
        declarations.add(new UniDrParameter("p_min","min","Минимум",Kind.SCALAR,ScalarType.DECIMAL,null,true,true,ValueSource.FORM,InitialRule.VALUE,List.of("1.234567890123"),Computed.NONE,null,null));
        var tree=FilterGroup.or(new FilterConditionNode(new FilterCondition("quantity",FilterOperator.GE,":min",null,FilterDataType.NUMBER)),
            new FilterConditionNode(new FilterCondition("quantity",FilterOperator.LT,"2",null,FilterDataType.NUMBER)));
        var original=base.withParameters(declarations).withFilterTree(tree).withComputedColumns(List.of(new ComputedColumn("calc_cost","Стоимость",Computation.FORMULA,"{quantity} * 10")))
            .withFieldStyles(Map.of("quantity",new FieldStyle(150,UniDrDefinition.Alignment.RIGHT,"0.000",false))).withRowNumbers(true);
        var copy=original.withTitle("Копия").withRowNumbers(false).withRowNumbers(true);
        assertThat(copy.parameterDeclarations()).isEqualTo(original.parameterDeclarations());assertThat(copy.filterTree()).isEqualTo(tree);
        assertThat(copy.computedColumns()).isEqualTo(original.computedColumns());assertThat(copy.fieldStyles()).isEqualTo(original.fieldStyles());
        assertThat(codec.decode(codec.encode(copy))).isEqualTo(copy);
        var encoded=json.readTree(codec.encode(copy));assertThat(encoded.has("parameterDeclarations")).isFalse();assertThat(encoded.path("parameters").size()).isEqualTo(4);
        assertThat(encoded.path("filterTree").path("operator").asText()).isEqualTo("OR");
    }
    @Test void legacyQueryAndFilterParametersWithSameNameNeverMergeAndFormHintsAreNotDefaults()throws Exception {
        var old=(ObjectNode)json.readTree(Files.readString(Path.of("examples/unidr/receiving-items-v1.json")));
        old.put("sourceId","query");old.put("mode","DETAILS");old.put("title","По запросу");old.putArray("groups");old.putArray("sorts");old.putArray("measures");
        old.putArray("columns").addObject().put("fieldId","qty");
        old.putArray("parameters").addObject().put("id","unit").put("fieldId","qty").put("operator","GE").put("caption","Минимум").put("defaultRule","NONE");
        old.putArray("filters").addObject().put("fieldId","qty").put("operator","GE").putObject("value").put("kind","PARAMETER").put("parameterId","unit");
        var query=old.putObject("query");query.put("kind","MANUAL");query.put("jpql","select p.quantity as qty from Product p where p.quantity > :unit");
        query.putArray("parameters").addObject().put("name","unit").put("caption","Запрос").put("kind","SCALAR").put("valueSource","FORM").put("required",true).put("showOnForm",true).put("defaultValue","0").put("computed","NONE").put("position",0);
        var metadata=UniDrQuerySourceService.metadata(List.of(QueryField.scalar("qty",java.math.BigDecimal.class)));
        var queryCodec=new UniDrDefinitionJsonCodec(new UniDrDefinitionValidator(new UniDrSourceCatalog(List.of(),ignored->metadata)));
        var migrated=queryCodec.decode(old.toString());assertThat(migrated.parameterDeclarations()).hasSize(2);
        assertThat(migrated.parameterDeclarations()).extracting(UniDrParameter::id).doesNotHaveDuplicates();
        assertThat(migrated.parameterDeclarations()).extracting(UniDrParameter::name).doesNotHaveDuplicates();
        var declared=migrated.parameterDeclarations().stream().filter(p->p.legacy()==null).findFirst().orElseThrow();
        assertThat(declared.scalarType()).isEqualTo(ScalarType.DECIMAL);assertThat(declared.initialRule()).isEqualTo(InitialRule.NONE);assertThat(declared.initialValues()).isEmpty();
        assertThat(migrated.compatibilityFilterStage()).isEqualTo(CompatibilityStage.AFTER_READ);assertThat(migrated.filters()).hasSize(1);
        var written=json.readTree(queryCodec.encode(migrated));assertThat(written.path("query").has("parameters")).isFalse();
        assertThat(queryCodec.decode(written.toString())).isEqualTo(migrated);
    }
    @Test void invalidNativeTreeAndInternalOrDuplicateParameterListsAreRejected()throws Exception {
        var tree=(ObjectNode)json.readTree(codec.encode(UniDrDefinitions.receivingItemsSummary()));
        tree.putObject("filterTree").put("operator","AND").put("extra",true).putArray("children");
        assertThatThrownBy(()->codec.decode(tree.toString())).isInstanceOf(UniDrValidationException.class);
        tree.remove("filterTree");tree.putArray("parameterDeclarations");
        assertThatThrownBy(()->codec.decode(tree.toString())).isInstanceOf(UniDrValidationException.class);
    }
    @Test void namedReferencesIgnoreQuotedTextAndComments() {
        assertThat(org.ipro.reportstudio.query.JpqlParameters.names("select ':fake', \"a :alsoFake\", 'it''s :quoted' from Product p /* :hidden */ where p.id=:id -- :comment\n and p.name=:name"))
            .containsExactlyInAnyOrder("id","name");
    }
}
