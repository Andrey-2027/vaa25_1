package org.ipro.search;

import jakarta.persistence.EntityManager;
import org.ip.model.Nomenclature;
import org.ipro.metadata.MetadataResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GlobalSearchProviderRegistryTest {

    @Test
    void explicitProviderWinsAndUnregisteredSourceUsesJpaFallback() {
        GlobalSearchSource nomenclature = new GlobalSearchCatalog(
            new org.ip.config.GlobalSearchApplicationConfig().globalSearchConfig(),
            new MetadataResolver()).requireSource(Nomenclature.class);
        GlobalSearchProvider<Nomenclature> explicit = new NoOpProvider();

        GlobalSearchProviderRegistry registry = new GlobalSearchProviderRegistry(List.of(explicit));

        assertThat(registry.providerOf(nomenclature)).isSameAs(explicit);
        assertThat(registry.providerOf(new GlobalSearchSource(
            1, Nomenclature.class, List.of("code"), List.of(), "id", "Номенклатура")))
            .isInstanceOf(JpaGlobalSearchProvider.class);
    }

    @Test
    void duplicateProvidersForOneEntityFailFast() {
        NoOpProvider first = new NoOpProvider();
        NoOpProvider second = new NoOpProvider();

        assertThatThrownBy(() -> new GlobalSearchProviderRegistry(List.of(first, second)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("уже зарегистрирован");
    }

    private static final class NoOpProvider implements GlobalSearchProvider<Nomenclature> {
        @Override
        public Class<Nomenclature> entityClass() {
            return Nomenclature.class;
        }

        @Override
        public List<Nomenclature> search(EntityManager entityManager, GlobalSearchSource source,
                                         String term, int limit, int timeoutMs) {
            return List.of();
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
        public GlobalSearchMatch classify(Nomenclature entity, GlobalSearchSource source, String term) {
            return new GlobalSearchMatch(GlobalSearchMatchKind.SUBSTRING, "code");
        }
    }
}
