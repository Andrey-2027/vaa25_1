package org.ipro.rest.gateway;

import org.ipro.rest.api.RestResourceBuilder;
import org.ipro.rest.api.RestResourceDefinition;
import org.ipro.rest.catalog.ResolvedRestResource;
import org.ipro.rest.catalog.RestResourceCatalog;
import org.ipro.rest.catalog.RestResourceKey;
import org.ipro.rest.service.RestDetailQuery;
import org.ipro.rest.service.RestDetailResult;
import org.ipro.rest.service.RestListQuery;
import org.ipro.rest.service.RestPageResult;
import org.ipro.rest.service.RestReadException;
import org.ipro.rest.service.RestReadOutcome;
import org.ipro.rest.service.RestReadService;
import org.ipro.rls.RlsContext;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.c5.C5PermissionEvaluator;
import org.ipro.rls.c5.C5PermissionKeys;
import org.ipro.rls.c5.C5PermissionConfigurationException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.dao.QueryTimeoutException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * INTERNAL default implementation of {@link RestReadGateway} (F-REST-READ-4 §3.2/§3.4).
 *
 * <p>The constructor freezes the published schema facts from the catalog, so runtime reads
 * never reach JPA types. {@code list}/{@code detail} run authentication, resource resolution,
 * API and root-entity gates before the parser callback; the remaining pipeline stays the
 * existing {@link RestReadService}, which re-checks the admission boundary on purpose.
 * The parser exception is not intercepted: the surrounding read-only transaction rolls back.</p>
 */
public class DefaultRestReadGateway implements RestReadGateway {

    private static final String ID_ALIAS = "id";

    private final RestReadService readService;
    private final RlsCurrentUser currentUser;
    private final C5PermissionEvaluator c5Evaluator;
    private final Map<RestResourceKey, PublishedResource> byKey;
    private final List<RestReadResourceSchema> published;
    private final int maxWindow;

    public DefaultRestReadGateway(RestResourceCatalog catalog,
                                  RestReadService readService,
                                  RlsCurrentUser currentUser,
                                  C5PermissionEvaluator c5Evaluator,
                                  int maxWindow) {
        if (maxWindow <= 0) {
            throw new IllegalArgumentException("Property 'platform.rest.read.max-window' must be positive: " + maxWindow);
        }
        Objects.requireNonNull(catalog, "catalog must not be null");
        this.readService = Objects.requireNonNull(readService, "readService must not be null");
        this.maxWindow = maxWindow;
        boolean executable = !catalog.resources().isEmpty();
        this.currentUser = executable ? Objects.requireNonNull(currentUser, "currentUser must not be null") : currentUser;
        this.c5Evaluator = executable ? Objects.requireNonNull(c5Evaluator, "c5Evaluator must not be null") : c5Evaluator;

        Map<RestResourceKey, PublishedResource> resolved = new LinkedHashMap<>();
        List<RestReadResourceSchema> schemas = new ArrayList<>();
        for (ResolvedRestResource resource : catalog.resources()) {
            RestReadResourceSchema schema = schemaOf(resource);
            resolved.put(resource.key(), new PublishedResource(schema, resource.definition().resourceType()));
            schemas.add(schema);
        }
        this.byKey = Collections.unmodifiableMap(resolved);
        this.published = List.copyOf(schemas);
    }

    @Override
    public List<RestReadResourceSchema> publishedResources() {
        return published;
    }

    @Override
    public Optional<RestReadResourceSchema> resolve(String resource, int major) {
        PublishedResource publishedResource = byKey.get(keyOrNull(resource, major));
        return publishedResource == null ? Optional.empty() : Optional.of(publishedResource.schema());
    }

    @Override
    @Transactional(readOnly = true)
    public RestPageResult list(String resource, int major, RestListRequestFactory requestFactory) {
        Objects.requireNonNull(requestFactory, "requestFactory must not be null");
        PublishedResource publishedResource = authorize(resource, major, "LIST");
        RestListQuery query = requestFactory.create(publishedResource.schema().list());
        requireSameTarget(resource, major, query.resource(), query.major());
        return readService.list(query);
    }

    @Override
    @Transactional(readOnly = true)
    public RestDetailResult detail(String resource, int major, RestDetailRequestFactory requestFactory) {
        Objects.requireNonNull(requestFactory, "requestFactory must not be null");
        PublishedResource publishedResource = authorize(resource, major, "DETAIL");
        RestDetailQuery query = requestFactory.create(publishedResource.schema().detail());
        requireSameTarget(resource, major, query.resource(), query.major());
        return readService.detail(query);
    }

    /**
     * Admission gates in the reviewed order: authentication (401) before resolution (404)
     * before API and root-entity grants (403). A revoked grant therefore denies the request
     * here and again inside the read boundary.
     */
    private PublishedResource authorize(String resource, int major, String operation) {
        try {
            return authorizeInternal(resource, major, operation);
        } catch (C5PermissionConfigurationException | DataAccessResourceFailureException
                 | TransientDataAccessResourceException | QueryTimeoutException unavailable) {
            throw new RestReadException(RestReadOutcome.SECURITY_UNAVAILABLE,
                "REST admission enforcement is unavailable", unavailable);
        }
    }

    private PublishedResource authorizeInternal(String resource, int major, String operation) {
        String username = requireAuthenticatedUsername();
        PublishedResource publishedResource = byKey.get(keyOrNull(resource, major));
        if (publishedResource == null) {
            throw new RestReadException(RestReadOutcome.RESOURCE_NOT_FOUND,
                "Resource not found: " + resource + " v" + major);
        }
        if (!c5Evaluator.isApiPermitted(username, resource, major, operation)) {
            throw new RestReadException(RestReadOutcome.FORBIDDEN,
                "Access denied for API operation: REST:" + resource + ":v" + major + ":" + operation);
        }
        Class<?> rootType = publishedResource.rootType();
        if (!c5Evaluator.isEntityReadPermitted(username, rootType)) {
            throw new RestReadException(RestReadOutcome.FORBIDDEN,
                "Access denied for entity: " + C5PermissionKeys.entityRead(rootType));
        }
        return publishedResource;
    }

    private String requireAuthenticatedUsername() {
        if (RlsContext.isBypassed()) {
            throw new RestReadException(RestReadOutcome.FORBIDDEN,
                "Privileged RLS bypass is not permitted for REST read operations");
        }
        if (currentUser == null) {
            throw new RestReadException(RestReadOutcome.UNAUTHENTICATED,
                "Authenticated subject is unavailable for REST read");
        }
        String username = currentUser.username();
        if (username == null || username.isBlank() || "system".equalsIgnoreCase(username)
            || "anonymous".equalsIgnoreCase(username)) {
            throw new RestReadException(RestReadOutcome.UNAUTHENTICATED,
                "Authenticated subject is required for REST read");
        }
        return username;
    }

    private static void requireSameTarget(String resource, int major, String queryResource, int queryMajor) {
        if (!resource.equals(queryResource) || major != queryMajor) {
            throw new IllegalArgumentException("Request factory must build a query for the resolved target "
                + resource + " v" + major + ": got " + queryResource + " v" + queryMajor);
        }
    }

    private static RestResourceKey keyOrNull(String resource, int major) {
        if (resource == null || resource.isBlank() || major < 1) {
            return null;
        }
        return new RestResourceKey(resource, major);
    }

    private RestReadResourceSchema schemaOf(ResolvedRestResource resource) {
        RestResourceDefinition<?> definition = resource.definition();
        Map<String, RestResourceDefinition.Field> declared = definition.fields();
        RestListContract list = new RestListContract(
            definition.resourceKey(), definition.majorVersion(),
            fieldFacts(definition.listFields(), declared),
            fieldFacts(definition.listDefaultFields(), declared),
            filterFacts(definition.filters()),
            definition.sortFields(),
            definition.defaultSort().map(RestResourceDefinition.Sort::field).orElse(null),
            definition.defaultSort().map(RestResourceDefinition.Sort::direction).orElse(null),
            definition.defaultPageSize(),
            Math.min(RestResourceBuilder.PLATFORM_MAX_PAGE_SIZE, definition.maxPageSize()),
            maxWindow);
        RestDetailContract detail = new RestDetailContract(
            definition.resourceKey(), definition.majorVersion(),
            fieldFacts(definition.detailFields(), declared),
            fieldFacts(definition.detailDefaultFields(), declared),
            fieldFact(Objects.requireNonNull(declared.get(ID_ALIAS),
                "Validated catalog must declare an id field for " + definition.resourceKey())));
        return new RestReadResourceSchema(definition.resourceKey(), definition.majorVersion(), list, detail);
    }

    private static List<RestFieldFact> fieldFacts(List<String> aliases,
                                                  Map<String, RestResourceDefinition.Field> declared) {
        List<RestFieldFact> facts = new ArrayList<>(aliases.size());
        for (String alias : aliases) {
            RestResourceDefinition.Field field = declared.get(alias);
            if (field == null) {
                throw new IllegalStateException("Validated field set references an unknown alias: " + alias);
            }
            facts.add(fieldFact(field));
        }
        return List.copyOf(facts);
    }

    private static RestFieldFact fieldFact(RestResourceDefinition.Field field) {
        return new RestFieldFact(field.name(), field.type(), field.format(), field.nullability());
    }

    private static Map<String, RestFilterFact> filterFacts(Map<String, RestResourceDefinition.Filter> filters) {
        Map<String, RestFilterFact> facts = new LinkedHashMap<>();
        filters.forEach((name, filter) -> facts.put(name,
            new RestFilterFact(name, filter.valueType(), filter.operator())));
        return facts;
    }

    /** Schema facts paired with the root type used only for the admission gate. */
    private record PublishedResource(RestReadResourceSchema schema, Class<?> rootType) { }
}
