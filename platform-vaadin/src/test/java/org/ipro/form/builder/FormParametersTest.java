package org.ipro.form.builder;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class FormParametersTest {
    private static final FormParameter<List<Class<?>>> SECTIONS = FormParameter.sections("sections", String.class, Integer.class);
    private FormParameters declaration() { var result = new FormParameters(); result.add(SECTIONS); return result; }

    @Test void defaultsAndTypedValuesAreValidatedAndIsolated() {
        var first = declaration(); first.bind(Map.of());
        assertThat(first.get(SECTIONS)).isEmpty();
        var input = new ArrayList<Class<?>>(); input.add(String.class);
        var second = declaration(); second.bind(FormParameters.values(SECTIONS.with(input)));
        input.add(Integer.class);
        assertThat(second.get(SECTIONS)).containsExactly(String.class);
        assertThat(first.get(SECTIONS)).isEmpty();
        assertThatThrownBy(() -> second.get(SECTIONS).clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void compatibilityMapValidatesContainerAndEveryMember() {
        for (Object bad : List.of("String", List.of("String"), List.of(Long.class), Set.of(String.class)))
            assertThatThrownBy(() -> declaration().bind(Map.of("sections", bad)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sections");
        assertThatThrownBy(() -> SECTIONS.with(List.of(Long.class))).hasMessageContaining("Unknown section");
    }
    @Test void unknownBusinessParametersFailButOpeningDataPassesThrough() {
        assertThatThrownBy(() -> declaration().bind(Map.of("sectionz", List.of(String.class))))
            .hasMessageContaining("Unknown form parameter: sectionz");
        var p = declaration();
        p.bind(Map.of("presetEntity", new Object(), "presetRows", Map.of(), "initialValues", Map.of(), "readOnly", true));
        assertThat(p.get(SECTIONS)).isEmpty();
    }
    @Test void duplicateReservedAndUndeclaredKeysFail() {
        var p = declaration();
        assertThatThrownBy(() -> p.add(SECTIONS)).hasMessageContaining("Duplicate");
        assertThatThrownBy(() -> p.add(FormParameter.of("readOnly", Boolean.class, false))).hasMessageContaining("Reserved");
        assertThatThrownBy(() -> p.get(FormParameter.sections("sections", String.class))).hasMessageContaining("Undeclared");
        assertThatThrownBy(() -> FormParameters.values(SECTIONS.with(List.of()), SECTIONS.with(List.of())))
            .hasMessageContaining("Duplicate");
    }
    @Test void scalarParametersHaveRuntimeTypeChecksAndNullDefaults() {
        var mode = FormParameter.of("mode", Boolean.class, false);
        var p = new FormParameters(); p.add(mode);
        p.bind(Collections.singletonMap("mode", null));
        assertThat(p.get(mode)).isFalse();
        p.bind(FormParameters.values(mode.with(true))); assertThat(p.get(mode)).isTrue();
        assertThatThrownBy(() -> p.bind(Map.of("mode", "true"))).hasMessageContaining("java.lang.Boolean");
    }
}
