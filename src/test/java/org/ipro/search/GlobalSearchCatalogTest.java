package org.ipro.search;

import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ip.model.ReceivingDocument;
import org.ip.model.User;
import org.ipro.data.EntityExposure;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GlobalSearchCatalogTest {

    @Test
    void sourcesUseExplicitParticipationCanonicalFieldsAndStableOrder() {
        GlobalSearchCatalog catalog = GlobalSearchTestSupport.catalog();

        assertThat(catalog.sources()).extracting(GlobalSearchSource::entityClass)
            .containsExactly(Nomenclature.class, PrdSpec.class, ReceivingDocument.class);
        assertThat(catalog.sources()).extracting(GlobalSearchSource::declarationOrder)
            .containsExactly(100, 200, 300);
        assertThat(catalog.requireSource(Nomenclature.class).idFieldName()).isEqualTo("id");
        assertThat(catalog.requireSource(Nomenclature.class).searchFields())
            .containsExactly("code", "name");
        assertThat(catalog.requireSource(PrdSpec.class).searchFields())
            .containsExactly("codeSpec", "draft");
        assertThat(catalog.requireSource(PrdSpec.class).additionalPaths()).isEmpty();
        assertThat(catalog.requireSource(ReceivingDocument.class).searchFields())
            .containsExactly("number");
        assertThat(catalog.requireSource(Nomenclature.class).groupTitle())
            .isEqualTo("Номенклатура");
    }

    @Test
    void sourceOrderDoesNotDependOnManagedEntityDiscoveryOrder() {
        GlobalSearchCatalog reversed = GlobalSearchTestSupport.catalog(
            List.of(ReceivingDocument.class, PrdSpec.class, Nomenclature.class));

        assertThat(reversed.sources()).extracting(GlobalSearchSource::entityClass)
            .containsExactly(Nomenclature.class, PrdSpec.class, ReceivingDocument.class);
    }

    @Test
    void managedEntityMembershipDoesNotOptAnEntityIntoGlobalSearch() {
        GlobalSearchCatalog catalog = GlobalSearchTestSupport.catalog(
            List.of(Nomenclature.class, PrdSpec.class, ReceivingDocument.class, User.class));

        assertThat(catalog.sourceOf(User.class)).isEmpty();
        assertThat(catalog.sources()).hasSize(3);
    }

    @Test
    void optedInNonStandardRootFailsAtStartup() {
        assertThatThrownBy(() -> GlobalSearchTestSupport.catalog(
            List.of(PrdSpec.class), EntityExposure.INTERNAL_STORE, List.of()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("только для STANDARD_ROOT");
    }

    @Test
    void customProviderMustBelongToAnOptedInSource() {
        GlobalSearchProvider<User> provider = new UserProvider();

        assertThatThrownBy(() -> GlobalSearchTestSupport.catalog(
            List.of(Nomenclature.class, PrdSpec.class, ReceivingDocument.class, User.class),
            EntityExposure.STANDARD_ROOT, List.of(provider)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("не объявлен через @GlobalSearchable");
    }

    @Test
    void nonPositiveProviderTimeoutFailsAtCatalogCreation() {
        assertThatThrownBy(() -> GlobalSearchTestSupport.catalog(
            GlobalSearchTestSupport.APPLICATION_TYPES, EntityExposure.STANDARD_ROOT,
            List.of(new InvalidTimeoutProvider())))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("queryTimeoutMs должен быть больше нуля");
    }

    private static final class InvalidTimeoutProvider implements GlobalSearchProvider<Nomenclature> {
        @Override
        public Class<Nomenclature> entityClass() {
            return Nomenclature.class;
        }

        @Override
        public int queryTimeoutMs() {
            return 0;
        }

        @Override
        public Object idOf(Nomenclature entity) {
            return entity.getId();
        }

        @Override
        public String displayValue(Nomenclature entity, GlobalSearchSource source) {
            return entity.getDisplayName();
        }

        @Override
        public GlobalSearchMatch classify(Nomenclature entity, GlobalSearchSource source,
                                          String term) {
            return new GlobalSearchMatch(GlobalSearchMatchKind.SUBSTRING, "name");
        }
    }

    private static final class UserProvider implements GlobalSearchProvider<User> {
        @Override
        public Class<User> entityClass() {
            return User.class;
        }

        @Override
        public Object idOf(User entity) {
            return entity.getId();
        }

        @Override
        public String displayValue(User entity, GlobalSearchSource source) {
            return entity.getUsername();
        }

        @Override
        public GlobalSearchMatch classify(User entity, GlobalSearchSource source, String term) {
            return new GlobalSearchMatch(GlobalSearchMatchKind.SUBSTRING, "username");
        }
    }
}
