package org.ipro.search;

import org.ip.config.GlobalSearchApplicationConfig;
import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ip.model.PrdSpecMtr;
import org.ip.model.ReceivingDocument;
import org.ip.model.User;
import org.ipro.metadata.MetadataResolver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GlobalSearchCatalogTest {

    private final MetadataResolver metadataResolver = new MetadataResolver();

    @Test
    void applicationSourcesKeepDeclarationOrderAndValidatedFields() {
        GlobalSearchConfig config = new GlobalSearchApplicationConfig().globalSearchConfig();

        GlobalSearchCatalog catalog = new GlobalSearchCatalog(config, metadataResolver);

        assertThat(catalog.sources()).extracting(GlobalSearchSource::entityClass)
            .containsExactly(Nomenclature.class, PrdSpec.class, ReceivingDocument.class);
        assertThat(catalog.sources()).extracting(GlobalSearchSource::declarationOrder)
            .containsExactly(0, 1, 2);
        assertThat(catalog.requireSource(Nomenclature.class).idFieldName())
            .isEqualTo("id");
        assertThat(catalog.requireSource(Nomenclature.class).searchFields())
            .containsExactly("code", "name");
        assertThat(catalog.requireSource(PrdSpec.class).searchFields())
            .containsExactly("codeSpec", "draft");
        assertThat(catalog.requireSource(PrdSpec.class).displayFields())
            .containsExactly("codeSpec", "draft");
        assertThat(catalog.requireSource(ReceivingDocument.class).displayFields())
            .containsExactly("number", "date");
        assertThat(catalog.requireSource(Nomenclature.class).usesDisplayName()).isTrue();
        assertThat(catalog.requireSource(ReceivingDocument.class).usesDisplayName()).isFalse();
    }

    @Test
    void duplicateEntityDeclarationFailsFast() {
        GlobalSearchConfig config = new GlobalSearchConfig();
        config.add(Nomenclature.class, "code");
        config.add(Nomenclature.class, "name");

        assertThatThrownBy(() -> new GlobalSearchCatalog(config, metadataResolver))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("добавлена повторно");
    }

    @Test
    void unknownSearchFieldFailsFast() {
        GlobalSearchConfig config = new GlobalSearchConfig();
        config.add(Nomenclature.class, "missing");

        assertThatThrownBy(() -> new GlobalSearchCatalog(config, metadataResolver))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("missing");
    }

    @Test
    void relationshipCannotBeSearchFieldInMvp() {
        GlobalSearchConfig config = new GlobalSearchConfig();
        config.add(PrdSpec.class, "nomenclature");

        assertThatThrownBy(() -> new GlobalSearchCatalog(config, metadataResolver))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("только прямые String-поля");
    }

    @Test
    void tableSectionCannotBeOrdinarySearchSource() {
        GlobalSearchConfig config = new GlobalSearchConfig();
        config.add(PrdSpecMtr.class, "typeMtr");

        assertThatThrownBy(() -> new GlobalSearchCatalog(config, metadataResolver))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("строки табличных частей");
    }

    @Test
    void transientFieldCannotBeSearchField() {
        GlobalSearchConfig config = new GlobalSearchConfig();
        config.add(User.class, "rawPassword");

        assertThatThrownBy(() -> new GlobalSearchCatalog(config, metadataResolver))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("не сохраняется JPA");
    }

    @Test
    void entityWithoutDisplayContractFailsFast() {
        GlobalSearchConfig config = new GlobalSearchConfig();
        config.add(User.class, "username");

        assertThatThrownBy(() -> new GlobalSearchCatalog(config, metadataResolver))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("displayFields");
    }

    @Test
    void catalogIsIndependentFromLaterConfigMutation() {
        GlobalSearchConfig config = new GlobalSearchConfig();
        config.add(Nomenclature.class, "code");
        GlobalSearchCatalog catalog = new GlobalSearchCatalog(config, metadataResolver);

        config.add(PrdSpec.class, "codeSpec");

        assertThat(catalog.sources()).hasSize(1);
        assertThat(catalog.sourceOf(PrdSpec.class)).isEmpty();
    }
}
