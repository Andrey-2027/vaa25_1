package org.ipro.rest.web.rls;

import org.ipro.rls.RlsReadableIdsCache;
import org.ipro.rls.RlsReadableIdsResolver;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Objects;

/**
 * HTTP-context-aware readable-ids source (F-REST-READ-4 §4.2).
 *
 * <p>A request marked by {@link RestRequestContext} is served by the request-scoped
 * {@link RestReadableIdsCache}; every other request is delegated to the session-scoped
 * {@link RlsReadableIdsCache}, which is dereferenced lazily and only on that UI path. Because the
 * web auto-configuration runs before {@code RlsAutoConfiguration}, this bean replaces the
 * delegating default resolver, and two independent resolver beans stop startup at the activator
 * instead of being chosen silently.</p>
 */
public class RestAwareRlsReadableIdsResolver implements RlsReadableIdsResolver {

    private final ObjectProvider<RestReadableIdsCache> requestCacheProvider;
    private final ObjectProvider<RlsReadableIdsCache> sessionCacheProvider;

    public RestAwareRlsReadableIdsResolver(ObjectProvider<RestReadableIdsCache> requestCacheProvider,
                                           ObjectProvider<RlsReadableIdsCache> sessionCacheProvider) {
        this.requestCacheProvider = Objects.requireNonNull(requestCacheProvider,
            "requestCacheProvider must not be null");
        this.sessionCacheProvider = Objects.requireNonNull(sessionCacheProvider,
            "sessionCacheProvider must not be null");
    }

    @Override
    public List<Long> getReadableIds(String dimension, String username) {
        if (RestRequestContext.isActive()) {
            return requestCacheProvider.getObject().getReadableIds(dimension, username);
        }
        RlsReadableIdsCache sessionCache = sessionCacheProvider.getIfAvailable();
        if (sessionCache == null) {
            throw new IllegalStateException("REST read resolver is active outside the REST request "
                + "namespace, but no session-scoped RlsReadableIdsCache is available: a UI-path "
                + "read cannot resolve readable ids");
        }
        return sessionCache.getReadableIds(dimension, username);
    }
}
