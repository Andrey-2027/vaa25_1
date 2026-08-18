package org.ipro.numbering;

import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ip.model.ReceivingDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Юнит-тест каталога {@code @Numbered}-полей: скан {@code @EntityMetadata}-классов приложения
 * (basePackage {@code org.ip}), сортировка по ключу, отличие GLOBAL-серий от scoped.
 */
class NumberingMetadataRegistryTest {

    @Test
    void catalogsNumberedFieldsOfEntityMetadataClassesSortedByKey() {
        NumberingMetadataRegistry registry = new NumberingMetadataRegistry("org.ip");
        registry.afterPropertiesSet();

        List<NumberingMetadataRegistry.NumberedFieldInfo> all = registry.all();

        assertThat(all).extracting(NumberingMetadataRegistry.NumberedFieldInfo::key)
            .containsExactly(
                "Nomenclature.code",
                "PrdSpec.codeSpec",
                "ReceivingDocument.number");
    }

    @Test
    void exposesScopeForScopedSeriesAndEmptyForGlobal() {
        NumberingMetadataRegistry registry = new NumberingMetadataRegistry("org.ip");
        registry.afterPropertiesSet();

        NumberingMetadataRegistry.NumberedFieldInfo document =
            registry.all().stream()
                .filter(f -> f.entityClass() == ReceivingDocument.class)
                .findFirst().orElseThrow();
        assertThat(document.annotation().scope()).containsExactly("JOURNAL");
        assertThat(document.annotation().period()).isEqualTo(NumberingPeriod.YEAR);

        NumberingMetadataRegistry.NumberedFieldInfo global =
            registry.all().stream()
                .filter(f -> f.entityClass() == Nomenclature.class)
                .findFirst().orElseThrow();
        assertThat(global.annotation().scope()).isEmpty();
        assertThat(global.annotation().period()).isEqualTo(NumberingPeriod.NEVER);

        assertThat(registry.all().stream()
            .filter(f -> f.entityClass() == PrdSpec.class)
            .findFirst().orElseThrow().annotation().scope()).isEmpty();
    }

    @Test
    void scanWithoutEntityMetadataClassesIsEmpty() {
        NumberingMetadataRegistry registry = new NumberingMetadataRegistry("org.ipro.numbering");
        registry.afterPropertiesSet();

        assertThat(registry.all()).isEmpty();
    }
}
