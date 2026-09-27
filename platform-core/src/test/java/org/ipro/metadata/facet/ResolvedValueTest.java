package org.ipro.metadata.facet;

import org.ipro.metadata.FactOrigin;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResolvedValueTest {

    @Test
    void compatibilityConstructorUsesUnknownOriginWithoutInventingSymbol() {
        ResolvedValue value = new ResolvedValue("value", FactSource.OVERRIDE);

        assertThat(value.value()).isEqualTo("value");
        assertThat(value.source()).isEqualTo(FactSource.OVERRIDE);
        assertThat(value.origin()).isEqualTo(FactOrigin.UNKNOWN);
        assertThat(value.symbol()).isEmpty();
    }

    @Test
    void codeFactoryWithoutOriginKeepsTheUnknownFallback() {
        ResolvedValue value = ResolvedValue.code("value");

        assertThat(value.source()).isEqualTo(FactSource.CODE);
        assertThat(value.origin()).isEqualTo(FactOrigin.UNKNOWN);
        assertThat(value.symbol()).isEmpty();
    }

    @Test
    void codeFactoryCanCarryKnownOriginWithoutSymbol() {
        ResolvedValue value = ResolvedValue.code("value", FactOrigin.EXPLICIT);

        assertThat(value.source()).isEqualTo(FactSource.CODE);
        assertThat(value.origin()).isEqualTo(FactOrigin.EXPLICIT);
        assertThat(value.symbol()).isEmpty();
    }

    @Test
    void factFactoryPreservesOriginAndSymbol() {
        ResolvedValue value = ResolvedValue.fact(
            "value", FactOrigin.DERIVED, "org.example.Entity#code");

        assertThat(value.source()).isEqualTo(FactSource.CODE);
        assertThat(value.origin()).isEqualTo(FactOrigin.DERIVED);
        assertThat(value.symbol()).isEqualTo("org.example.Entity#code");
    }

    @Test
    void overrideLayerDoesNotReplaceOriginOfCodeDefault() {
        ResolvedValue value = new ResolvedValue(
            "overridden", FactSource.OVERRIDE, FactOrigin.REGISTRATION,
            "org.example.Entity#name");

        assertThat(value.source()).isEqualTo(FactSource.OVERRIDE);
        assertThat(value.origin()).isEqualTo(FactOrigin.REGISTRATION);
        assertThat(value.symbol()).isEqualTo("org.example.Entity#name");
    }

    @Test
    void nullValueAndSymbolAreNormalizedToEmptyStrings() {
        ResolvedValue value = ResolvedValue.fact(null, FactOrigin.EXPLICIT, null);

        assertThat(value.value()).isEmpty();
        assertThat(value.symbol()).isEmpty();
        assertThat(value.origin()).isEqualTo(FactOrigin.EXPLICIT);
    }
}
