package org.ipro.rest.web.rls;

import org.ipro.rls.AccessGrantVersion;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsReadableIdsCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-REST-READ-4 §4.2: the request-scoped cache keeps the session cache semantics (AccessService,
 * username, grant version) while its lifetime is one request; the REST request never dereferences
 * the session cache and a UI request never touches the request-scoped one.
 */
class RestRequestRlsIntegrationTest {

    @AfterEach
    void clearAttributes() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void requestCacheCachesPerDimensionAndRecomputesForAnotherSubject() {
        AccessService accessService = mock(AccessService.class);
        when(accessService.getReadableIds("JOURNAL", "alice")).thenReturn(List.of(1L));
        when(accessService.getReadableIds("BRANCH", "alice")).thenReturn(List.of(11L));
        when(accessService.getReadableIds("JOURNAL", "bob")).thenReturn(List.of(2L));
        RestReadableIdsCache cache = new RestReadableIdsCache(accessService);

        assertThat(cache.getReadableIds("JOURNAL", "alice")).containsExactly(1L);
        assertThat(cache.getReadableIds("JOURNAL", "alice")).containsExactly(1L);
        assertThat(cache.getReadableIds("BRANCH", "alice")).containsExactly(11L);
        verify(accessService, times(1)).getReadableIds("JOURNAL", "alice");
        verify(accessService, times(1)).getReadableIds("BRANCH", "alice");

        // Другой субъект не должен увидеть ids предыдущего.
        assertThat(cache.getReadableIds("JOURNAL", "bob")).containsExactly(2L);
        assertThat(cache.getReadableIds("JOURNAL", "alice")).containsExactly(1L);
        verify(accessService, times(2)).getReadableIds("JOURNAL", "alice");
    }

    @Test
    void requestCachePreservesWildcardAndInvalidatesOnGrantVersion() {
        AccessService accessService = mock(AccessService.class);
        when(accessService.getReadableIds("JOURNAL", "alice")).thenReturn(null);
        RestReadableIdsCache cache = new RestReadableIdsCache(accessService);

        assertThat(cache.getReadableIds("JOURNAL", "alice")).isNull();
        assertThat(cache.getReadableIds("JOURNAL", "alice")).isNull();
        verify(accessService, times(1)).getReadableIds("JOURNAL", "alice");

        AccessGrantVersion.bump();
        assertThat(cache.getReadableIds("JOURNAL", "alice")).isNull();
        verify(accessService, times(2)).getReadableIds("JOURNAL", "alice");
    }

    @Test
    @SuppressWarnings("unchecked")
    void restRequestUsesTheRequestCacheAndNeverTheSessionCache() {
        RestReadableIdsCache requestCache = mock(RestReadableIdsCache.class);
        when(requestCache.getReadableIds("JOURNAL", "alice")).thenReturn(List.of(7L));
        ObjectProvider<RestReadableIdsCache> requestProvider = mock(ObjectProvider.class);
        when(requestProvider.getObject()).thenReturn(requestCache);
        ObjectProvider<RlsReadableIdsCache> sessionProvider = mock(ObjectProvider.class);

        RestAwareRlsReadableIdsResolver resolver =
            new RestAwareRlsReadableIdsResolver(requestProvider, sessionProvider);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/specifications");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        RestRequestContext.enter(request);
        try {
            assertThat(resolver.getReadableIds("JOURNAL", "alice")).containsExactly(7L);
        } finally {
            RestRequestContext.exit(request);
        }

        verify(sessionProvider, never()).getIfAvailable();
    }

    @Test
    @SuppressWarnings("unchecked")
    void uiRequestDelegatesToTheSessionCacheLazily() {
        ObjectProvider<RestReadableIdsCache> requestProvider = mock(ObjectProvider.class);
        RlsReadableIdsCache sessionCache = mock(RlsReadableIdsCache.class);
        when(sessionCache.getReadableIds("JOURNAL", "alice")).thenReturn(List.of(9L));
        ObjectProvider<RlsReadableIdsCache> sessionProvider = mock(ObjectProvider.class);
        when(sessionProvider.getIfAvailable()).thenReturn(sessionCache);

        RestAwareRlsReadableIdsResolver resolver =
            new RestAwareRlsReadableIdsResolver(requestProvider, sessionProvider);

        assertThat(resolver.getReadableIds("JOURNAL", "alice")).containsExactly(9L);
        verify(requestProvider, never()).getObject();
    }

    @Test
    @SuppressWarnings("unchecked")
    void uiPathWithoutASessionCacheFailsClosed() {
        ObjectProvider<RestReadableIdsCache> requestProvider = mock(ObjectProvider.class);
        ObjectProvider<RlsReadableIdsCache> sessionProvider = mock(ObjectProvider.class);
        when(sessionProvider.getIfAvailable()).thenReturn(null);

        RestAwareRlsReadableIdsResolver resolver =
            new RestAwareRlsReadableIdsResolver(requestProvider, sessionProvider);

        assertThatThrownBy(() -> resolver.getReadableIds("JOURNAL", "alice"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("RlsReadableIdsCache");
    }
}
