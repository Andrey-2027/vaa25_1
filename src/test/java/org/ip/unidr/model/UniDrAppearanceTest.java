package org.ip.unidr.model;

import org.ip.unidr.model.UniDrDefinition.Appearance;
import org.ip.unidr.source.UniDrSourceCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class UniDrAppearanceTest {
    @Test void textAreasAreOptionalBoundedAndStrictlyTyped() throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var tree=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(json);
        var appearance=(com.fasterxml.jackson.databind.node.ObjectNode)tree.get("appearance");
        for(String name:java.util.List.of("reportHeader","pageHeader","pageFooter","reportFooter"))appearance.remove(name);
        assertThat(codec.decode(tree.toString()).appearance()).isEqualTo(Appearance.defaults());
        appearance.put("reportHeader","  \n ");
        assertThat(codec.decode(tree.toString()).appearance().reportHeader()).isNull();
        appearance.put("reportHeader",12);
        assertThatThrownBy(()->codec.decode(tree.toString())).isInstanceOf(UniDrValidationException.class).hasMessageContaining("appearance.reportHeader");
        appearance.put("reportHeader","x".repeat(4001));
        assertThatThrownBy(()->codec.decode(tree.toString())).isInstanceOf(UniDrValidationException.class).hasMessageContaining("appearance.reportHeader");
    }
    private final UniDrDefinitionJsonCodec codec=new UniDrDefinitionJsonCodec(new UniDrDefinitionValidator(UniDrSourceCatalog.pilot()));
    private final String json=codec.encode(UniDrDefinitions.receivingItemsSummary());
    @Test void flagsRoundTripAndOldV1DefinitionKeepsDefaults() {
        String without=json.replaceAll(",\"appearance\":\\{[^}]*}","");
        assertThat(codec.decode(without).appearance()).isEqualTo(Appearance.defaults());
        String changed=json.replace("\"rowBorders\":true","\"rowBorders\":false").replace("\"stripeRows\":true","\"stripeRows\":false");
        assertThat(codec.decode(changed).appearance()).isEqualTo(new Appearance(false,true,false));
        assertThat(codec.decode(codec.encode(codec.decode(changed)))).isEqualTo(codec.decode(changed));
    }
    @ParameterizedTest @ValueSource(strings={"null","false","{}","{\"rowBorders\":true,\"columnBorders\":true}","{\"rowBorders\":1,\"columnBorders\":true,\"stripeRows\":true}"})
    void malformedAppearanceIsNotSilentlyDefaulted(String appearance) {
        String malformed=json.replaceAll("\"appearance\":\\{[^}]*}","\"appearance\":"+appearance);
        assertThatThrownBy(()->codec.decode(malformed)).isInstanceOf(UniDrValidationException.class).hasMessageContaining("appearance");
    }
}
