package org.ipro.rest.gateway;

import org.ipro.rest.service.RestDetailResult;
import org.ipro.rest.service.RestPageResult;

import java.util.List;
import java.util.Optional;

/**
 * Reviewed module boundary (MODULE_API) of the server-side REST read pipeline
 * (F-REST-READ-4 §3.1).
 *
 * <p>The HTTP adapter resolves published {@code (resource, major)} pairs, receives immutable
 * schema facts and supplies request parsers that build the internal queries inside the same
 * read-only transaction. No JPA path, repository, {@code Class} or catalog type crosses this
 * boundary: the adapter sees only aliases, types and limits.</p>
 */
public interface RestReadGateway {

    /** Published pairs in stable order; wire facts only, without Java types. */
    List<RestReadResourceSchema> publishedResources();

    /** Route resolution; an empty Optional is the 404 equivalent and performs no grant check. */
    Optional<RestReadResourceSchema> resolve(String resource, int major);

    /**
     * Authentication/resource/API/entity gates, parser callback and the read pipeline in one
     * read-only transaction. A parser exception is not intercepted: it propagates and the
     * transaction rolls back without a write.
     */
    RestPageResult list(String resource, int major, RestListRequestFactory requestFactory);

    /** Detail counterpart of {@link #list(String, int, RestListRequestFactory)}. */
    RestDetailResult detail(String resource, int major, RestDetailRequestFactory requestFactory);
}
