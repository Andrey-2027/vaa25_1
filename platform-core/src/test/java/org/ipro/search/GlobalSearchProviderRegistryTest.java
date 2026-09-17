package org.ipro.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Владелец теста — platform-core: проверяется контракт реестра провайдеров, а не прикладные
 * сущности. Раньше тест использовал {@code org.ip.model.Nomenclature/PrdSpec}; реестру
 * достаточно любого типа, поэтому прикладные фикстуры заменены нейтральными маркерами,
 * а {@link GlobalSearchSource} собирается напрямую (это публичный record).
 */
class GlobalSearchProviderRegistryTest {

    @Test
    void explicitProviderWinsAndDifferentUnregisteredEntityUsesJpaFallback() {
        GlobalSearchSource custom = source(CustomEntity.class);
        GlobalSearchSource generic = source(GenericEntity.class);
        GlobalSearchProvider<CustomEntity> explicit = new NoOpProvider();

        GlobalSearchProviderRegistry registry = new GlobalSearchProviderRegistry(List.of(explicit));

        assertThat(registry.providerOf(custom)).isSameAs(explicit);
        assertThat(registry.providerOf(generic))
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

    private static GlobalSearchSource source(Class<?> entityClass) {
        return new GlobalSearchSource(0, entityClass,
            List.of("name"), "id", entityClass.getSimpleName());
    }

    /** Нейтральные типы: реестру нужен только Class, не JPA-сущность. */
    private static final class CustomEntity {
    }

    private static final class GenericEntity {
    }

    private static final class NoOpProvider implements GlobalSearchProvider<CustomEntity> {
        @Override
        public Class<CustomEntity> entityClass() {
            return CustomEntity.class;
        }

        @Override
        public Object idOf(CustomEntity entity) {
            return null;
        }

        @Override
        public String displayValue(CustomEntity entity, GlobalSearchSource source) {
            return "custom";
        }

        @Override
        public GlobalSearchMatch classify(CustomEntity entity, GlobalSearchSource source,
                                          String term) {
            return new GlobalSearchMatch(GlobalSearchMatchKind.SUBSTRING, "code");
        }
    }
}
