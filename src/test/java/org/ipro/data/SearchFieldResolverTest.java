package org.ipro.data;

import org.ip.model.Branch;
import org.ip.model.PrdSpec;
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
}
