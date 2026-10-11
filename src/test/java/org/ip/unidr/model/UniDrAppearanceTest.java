package org.ip.unidr.model;

import org.ip.unidr.model.UniDrDefinition.Appearance;
import org.ip.unidr.source.UniDrSourceCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class UniDrAppearanceTest {
    private final UniDrDefinitionJsonCodec codec=new UniDrDefinitionJsonCodec(new UniDrDefinitionValidator(UniDrSourceCatalog.pilot()));
    private final String json=codec.encode(UniDrDefinitions.receivingItemsSummary());
    @Test void flagsRoundTripAndOldV1DefinitionKeepsDefaults() {
        String without=json.replace(",\"appearance\":{\"rowBorders\":true,\"columnBorders\":true,\"stripeRows\":true}","");
        assertThat(codec.decode(without).appearance()).isEqualTo(Appearance.defaults());
        String changed=json.replace("\"rowBorders\":true","\"rowBorders\":false").replace("\"stripeRows\":true","\"stripeRows\":false");
        assertThat(codec.decode(changed).appearance()).isEqualTo(new Appearance(false,true,false));
        assertThat(codec.decode(codec.encode(codec.decode(changed)))).isEqualTo(codec.decode(changed));
    }
    @ParameterizedTest @ValueSource(strings={"null","false","{}","{\"rowBorders\":true,\"columnBorders\":true}","{\"rowBorders\":1,\"columnBorders\":true,\"stripeRows\":true}"})
    void malformedAppearanceIsNotSilentlyDefaulted(String appearance) {
        String malformed=json.replace("{\"rowBorders\":true,\"columnBorders\":true,\"stripeRows\":true}",appearance);
        assertThatThrownBy(()->codec.decode(malformed)).isInstanceOf(UniDrValidationException.class).hasMessageContaining("appearance");
    }
}
