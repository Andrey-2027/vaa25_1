package org.ipro.data;

import org.ip.model.Branch;
import org.ip.model.GridFormView;
import org.ip.model.PrdSpec;
import org.ip.model.UnitOfMeasurement;
import org.ipro.metadata.MetadataResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C4.4 (ADR-0007 §7): default search-field resolution — metadata-колонки без дублирования
 * в сервисах; явные поля проверяются, а не пропускаются молча.
 */
class SearchFieldResolverTest {

    private final SearchFieldResolver resolver =
        new SearchFieldResolver(new MetadataResolver());

    @Test
    void defaultsComeFromMetadataSelectColumns() {
        assertThat(resolver.resolve(Branch.class, List.of()))
            .containsExactly("code", "name");
    }

    @Test
    void typeLevelSearchFieldsAreSharedDefaultsAcrossContexts() {
        assertThat(resolver.resolve(PrdSpec.class, List.of()))
            .containsExactly("codeSpec", "draft")
            .doesNotContain("nomenclature.name");
    }

    /**
     * C4.6 волна C: поля поиска, которые раньше жили переопределением в сервисе
     * ({@code UnitOfMeasurementService}, {@code GridFormViewService}), объявлены на типе —
     * поэтому удаление сервиса не меняет набор и порядок полей.
     */
    @Test
    void searchFieldsDeclaredOnTypeReplaceServiceOverrides() {
        assertThat(resolver.resolve(UnitOfMeasurement.class, List.of()))
            .containsExactly("shortCode");
        // C4.6 волна E: SklNomOpa тоже объявил поля на типе вместо override в сервисе
        assertThat(resolver.resolve(org.ip.model.SklNomOpa.class, List.of()))
            .containsExactly("displayName");
        assertThat(resolver.resolve(GridFormView.class, List.of()))
            .containsExactly("name", "formKey");
    }

    @Test
    void explicitFieldsOverrideDefaults() {
        assertThat(resolver.resolve(Branch.class, List.of("name")))
            .containsExactly("name");
        assertThat(resolver.resolve(Branch.class, List.of("name", "name")))
            .containsExactly("name");
    }

    @Test
    void unknownExplicitFieldIsRejected() {
        assertThatThrownBy(() -> resolver.resolve(Branch.class, List.of("noSuchField")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("noSuchField");
    }

    @Test
    void nonStringExplicitFieldIsRejected() {
        assertThatThrownBy(() -> resolver.resolve(Branch.class, List.of("id")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("id");
    }

    @Test
    void blankExplicitFieldIsRejected() {
        assertThatThrownBy(() -> resolver.resolve(Branch.class, List.of(" ")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * C4.8: to-many в полях поиска запрещено политикой, и запрет исполняем, а не декларативен.
     * Путь через коллекцию не разворачивается: сама коллекция — не строковое поле, а
     * следующий сегмент на {@code java.util.List} не находится вовсе. В strict-режиме (list
     * и global search) это отказ, в tolerant (lookup, поля приходят из UI) — пропуск.
     */
    @Test
    void toManyPathIsNotAValidSearchField() {
        assertThatThrownBy(() -> resolver.resolve(PrdSpec.class, List.of("materials.nomenclature")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve(PrdSpec.class, List.of("materials")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("не является строковым");

        assertThat(resolver.resolve(PrdSpec.class, List.of("materials", "codeSpec"), false))
            .containsExactly("codeSpec");
    }
}
