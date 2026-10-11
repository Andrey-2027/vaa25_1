package org.ipro.rest.gateway;

import org.ipro.rest.service.RestListQuery;

/**
 * Web-side parser building the normalized list query from reviewed schema facts
 * (F-REST-READ-4 §3.1).
 *
 * <p>Malformed parameter shapes are rejected here (HTTP 400 semantics); the gateway does not
 * intercept the parser exception, so the read-only transaction rolls back.</p>
 */
@FunctionalInterface
public interface RestListRequestFactory {

    RestListQuery create(RestListContract contract);
}
