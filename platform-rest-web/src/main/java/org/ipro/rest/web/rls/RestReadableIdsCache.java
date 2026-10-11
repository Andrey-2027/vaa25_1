package org.ipro.rest.web.rls;

import org.ipro.rls.AccessGrantVersion;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsReadableIdsResolver;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Request-scoped counterpart of the session {@code RlsReadableIdsCache} (F-REST-READ-4 §4.2):
 * same {@link AccessService} source, same username/version semantics, but the state dies with
 * the request instead of outliving the credential. A stateless Basic request never creates or
 * reuses an HTTP session, and two consecutive or parallel subjects cannot observe each other's
 * readable ids.
 *
 * <p>Registered as a {@code @RequestScope} bean by the web auto-configuration; the container
 * clears it together with the request. Async dispatch is deliberately not part of the first
 * profile.</p>
 */
public class RestReadableIdsCache implements RlsReadableIdsResolver {

    /** Wraps a nullable result — {@code null} is the wildcard grant, ConcurrentHashMap rejects it. */
    private record CachedIds(List<Long> ids) {
    }

    private final AccessService accessService;
    private final Map<String, CachedIds> byDimension = new ConcurrentHashMap<>();
    private volatile long cachedVersion = -1;
    private volatile String cachedUsername;

    public RestReadableIdsCache(AccessService accessService) {
        this.accessService = Objects.requireNonNull(accessService, "accessService must not be null");
    }

    @Override
    public synchronized List<Long> getReadableIds(String dimension, String username) {
        long currentVersion = AccessGrantVersion.current();
        if (currentVersion != cachedVersion || !Objects.equals(username, cachedUsername)) {
            byDimension.clear();
            cachedVersion = currentVersion;
            cachedUsername = username;
        }
        return byDimension
            .computeIfAbsent(dimension, d -> new CachedIds(accessService.getReadableIds(d, username)))
            .ids();
    }
}
