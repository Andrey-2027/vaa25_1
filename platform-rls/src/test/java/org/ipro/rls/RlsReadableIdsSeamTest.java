package org.ipro.rls;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F-REST-READ-4 §4.1: seam readable-ids.
 *
 * <p>Session-scope кэш остаётся default UI-реализацией интерфейса, а оба конструктора
 * активатора принимают любой resolver — существующие вызовы с {@link RlsReadableIdsCache}
 * компилируются без правок, а HTTP-адаптер подменяет источник, не трогая активатор.</p>
 */
class RlsReadableIdsSeamTest {

    @Test
    void defaultResolverDoesNotDuplicateTheConcreteCacheBean() {
        RlsReadableIdsCache cache = mock(RlsReadableIdsCache.class);
        AccessService service = mock(AccessService.class);
        when(service.getReadableIds("JOURNAL", "alice")).thenReturn(List.of(7L));
        try (var context = new org.springframework.context.support.GenericApplicationContext()) {
            context.registerBean("rlsReadableIdsCache", RlsReadableIdsCache.class, () -> cache);
            context.registerBean("rlsReadableIdsResolver", RlsReadableIdsResolver.class,
                () -> new org.ipro.rls.config.RlsAutoConfiguration().rlsReadableIdsResolver(cache, service));
            context.refresh();

            assertThat(context.getBean(RlsReadableIdsCache.class)).isSameAs(cache);
            assertThat(context.getBeansOfType(RlsReadableIdsCache.class)).hasSize(1);
            assertThat(context.getBean("rlsReadableIdsResolver", RlsReadableIdsResolver.class)
                .getReadableIds("JOURNAL", "alice")).containsExactly(7L);
        }
    }

    @Test
    void outsideHttpUsesTheRealPolicyWithoutTouchingSessionCache() {
        RlsReadableIdsCache cache = mock(RlsReadableIdsCache.class);
        AccessService service = mock(AccessService.class);
        when(service.getReadableIds("DEPARTMENT", "alice")).thenReturn(List.of(1L));
        when(service.getReadableIds("DEPARTMENT", "bob")).thenReturn(List.of(2L));
        when(service.getReadableIds("DEPARTMENT", "nobody")).thenReturn(List.of(AccessService.NO_ACCESS_SENTINEL));
        var resolver = new org.ipro.rls.config.RlsAutoConfiguration().rlsReadableIdsResolver(cache, service);
        assertThat(resolver.getReadableIds("DEPARTMENT", "alice")).containsExactly(1L);
        assertThat(resolver.getReadableIds("DEPARTMENT", "bob")).containsExactly(2L);
        assertThat(resolver.getReadableIds("DEPARTMENT", "nobody")).containsExactly(AccessService.NO_ACCESS_SENTINEL);
        org.mockito.Mockito.verifyNoInteractions(cache);
    }

    @Test
    void httpRequestStillUsesSessionCache() {
        RlsReadableIdsCache cache = mock(RlsReadableIdsCache.class);
        AccessService service = mock(AccessService.class);
        when(cache.getReadableIds("DEPARTMENT", "alice")).thenReturn(List.of(1L));
        var attributes = mock(org.springframework.web.context.request.RequestAttributes.class);
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(attributes);
        try {
            var resolver = new org.ipro.rls.config.RlsAutoConfiguration().rlsReadableIdsResolver(cache, service);
            assertThat(resolver.getReadableIds("DEPARTMENT", "alice")).containsExactly(1L);
            org.mockito.Mockito.verifyNoInteractions(service);
        } finally { org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes(); }
    }

    @Test
    void sessionCacheIsAReadableIdsResolver() {
        AccessService accessService = mock(AccessService.class);
        when(accessService.getReadableIds("JOURNAL", "alice")).thenReturn(List.of(7L));

        RlsReadableIdsResolver resolver = new RlsReadableIdsCache(accessService);

        assertThat(resolver.getReadableIds("JOURNAL", "alice")).containsExactly(7L);
    }

    @Test
    void wildcardGrantRemainsNullThroughTheSeam() {
        AccessService accessService = mock(AccessService.class);
        when(accessService.getReadableIds("JOURNAL", "alice")).thenReturn(null);

        RlsReadableIdsResolver resolver = new RlsReadableIdsCache(accessService);

        assertThat(resolver.getReadableIds("JOURNAL", "alice")).isNull();
    }

    @Test
    void bothActivatorConstructorsAcceptAnyResolver() {
        RlsDimensionRegistry registry = mock(RlsDimensionRegistry.class);
        RlsCurrentUser currentUser = mock(RlsCurrentUser.class);
        RlsReadableIdsResolver resolver = (dimension, username) -> List.of(1L);

        assertThat(new RlsFilterActivator(registry, resolver, currentUser)).isNotNull();
        assertThat(new RlsFilterActivator(registry, resolver, currentUser,
            RlsBypassAudit.loggingOnly())).isNotNull();
    }
}
