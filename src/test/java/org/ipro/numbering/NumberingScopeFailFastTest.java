package org.ipro.numbering;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Стартовый fail-fast на резолвимость scope (§3.3, замечание ревью): каждый не-GLOBAL scope
 * из {@code @Numbered} обязан резолвиться активным NumberingScopeResolver, иначе
 * IllegalStateException при старте — а не тихий ключ счётчика "BRANCH:null" в проде.
 * Каталог берём реальный: Nomenclature.code/Oper.code (GLOBAL) + ReceivingDocument.number
 * (scope = JOURNAL).
 */
class NumberingScopeFailFastTest {

    private final NumberingMetadataRegistry registry = new NumberingMetadataRegistry("org.ip");

    private NumberingService service(NumberingScopeResolver resolver) {
        registry.rebuild();
        return new NumberingService(null, null, resolver, registry);
    }

    @Test
    void unresolvableScopeFailsStartup() {
        NumberingService service = service(new NumberingScopeResolver() {
            @Override
            public Long scopeValue(String dimension, Object entity) {
                return null;
            }

            @Override
            public boolean canResolve(String dimension) {
                return false;
            }
        });

        assertThatThrownBy(service::afterPropertiesSet)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("\"JOURNAL\"")
            .hasMessageContaining("ReceivingDocument.number");
    }

    @Test
    void resolvableScopePassesStartup() {
        NumberingService service = service(new NumberingScopeResolver() {
            @Override
            public Long scopeValue(String dimension, Object entity) {
                return null;
            }

            @Override
            public boolean canResolve(String dimension) {
                return "JOURNAL".equals(dimension);
            }
        });

        service.afterPropertiesSet();
    }

    @Test
    void defaultResolverAssumesItResolvesItsDimensions() {
        NumberingService service = service((dimension, entity) -> null);

        service.afterPropertiesSet();
    }

    @Test
    void globalOnlyFallbackDeclaresNothingResolvable() {
        NumberingScopeResolver fallback = NumberingService.globalOnlyDefault();

        assertThat(fallback.canResolve("JOURNAL")).isFalse();
        assertThat(fallback.scopeValue("JOURNAL", new Object())).isNull();

        NumberingService service = service(fallback);
        assertThatThrownBy(service::afterPropertiesSet)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("NumberingScopeResolver");
    }
}