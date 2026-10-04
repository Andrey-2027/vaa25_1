package org.ipro.rest.service;

import org.ipro.data.EntityReadAccess;
import org.ipro.rest.api.RestResourceDefinition;
import org.ipro.rest.api.RestSortDirection;
import org.ipro.rest.catalog.ResolvedRestField;
import org.ipro.rest.catalog.ResolvedRestPath;
import org.ipro.rest.catalog.ResolvedRestResource;
import org.ipro.rest.catalog.RestReadOperation;
import org.ipro.rest.catalog.RestResourceCatalog;
import org.ipro.rest.catalog.RestResourceProjection;
import org.ipro.rest.security.RestReferenceAuthorizationValidator;
import org.ipro.rls.RlsContext;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.c5.C5AccessDeniedException;
import org.ipro.rls.c5.C5PermissionEvaluator;
import org.ipro.rls.c5.C5PermissionConfigurationException;
import org.ipro.rls.c5.C5PermissionKeys;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Server-side REST read pipeline executing in a single read-only transaction (F-REST-READ-3 В§6).
 */
@Transactional(readOnly = true)
public class RestReadService {

    /** Server default for the exclusive end of a requested page; total may exceed it. */
    public static final int DEFAULT_MAX_WINDOW = 10_000;

    private static final int MAX_PAGE_SIZE = 200;

    private final RestResourceCatalog catalog;
    private final EntityReadAccess readAccess;
    private final C5PermissionEvaluator c5Evaluator;
    private final RlsCurrentUser currentUser;
    private final RestReferenceAuthorizationValidator referenceValidator;
    private final int maxWindow;

    public RestReadService(RestResourceCatalog catalog,
                           EntityReadAccess readAccess,
                           C5PermissionEvaluator c5Evaluator,
                           RlsCurrentUser currentUser,
                           RestReferenceAuthorizationValidator referenceValidator) {
        this(catalog, readAccess, c5Evaluator, currentUser, referenceValidator, DEFAULT_MAX_WINDOW);
    }

    public RestReadService(RestResourceCatalog catalog,
                           EntityReadAccess readAccess,
                           C5PermissionEvaluator c5Evaluator,
                           RlsCurrentUser currentUser,
                           RestReferenceAuthorizationValidator referenceValidator,
                           int maxWindow) {
        if (maxWindow <= 0) {
            throw new IllegalArgumentException("Property 'platform.rest.read.max-window' must be positive: " + maxWindow);
        }
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        for (ResolvedRestResource resource : catalog.resources()) {
            if (resource.definition().defaultPageSize() > maxWindow) {
                throw new IllegalArgumentException("Property 'platform.rest.read.max-window' (" + maxWindow
                    + ") is smaller than the default page size for " + resource.key()
                    + ": " + resource.definition().defaultPageSize());
            }
        }
        this.maxWindow = maxWindow;
        boolean executable = !catalog.resources().isEmpty();
        this.readAccess = executable ? Objects.requireNonNull(readAccess, "readAccess must not be null") : readAccess;
        this.c5Evaluator = executable ? Objects.requireNonNull(c5Evaluator, "c5Evaluator must not be null") : c5Evaluator;
        this.currentUser = executable ? Objects.requireNonNull(currentUser, "currentUser must not be null") : currentUser;
        this.referenceValidator = executable
            ? Objects.requireNonNull(referenceValidator, "referenceValidator must not be null") : referenceValidator;
    }

    public RestPageResult list(RestListQuery query) {
        Objects.requireNonNull(query, "query must not be null");

        // 1. РџРѕР»СѓС‡РёС‚СЊ РїРѕРґС‚РІРµСЂР¶РґС‘РЅРЅС‹Р№ СЃСѓР±СЉРµРєС‚ (РѕС‚РєР°Р· РїСЂРё RLS bypass)
        String username = requireAuthenticatedUsername();

        // 2. РќР°Р№С‚Рё РѕРїСѓР±Р»РёРєРѕРІР°РЅРЅСѓСЋ РїР°СЂСѓ resource/major
        ResolvedRestResource resource = catalog.find(query.resource(), query.major())
            .orElseThrow(() -> new RestReadException(RestReadOutcome.RESOURCE_NOT_FOUND,
                "Resource not found: " + query.resource() + " v" + query.major()));

        // 3. РџСЂРѕРІРµСЂРёС‚СЊ API grant
        if (!c5Evaluator.isApiPermitted(username, query.resource(), query.major(), "LIST")) {
            throw new RestReadException(RestReadOutcome.FORBIDDEN,
                "Access denied for API operation: REST:" + query.resource() + ":v" + query.major() + ":LIST");
        }

        // РџСЂРѕРІРµСЂРёС‚СЊ C5 Entity Read РЅР° root
        Class<?> rootType = resource.definition().resourceType();
        if (!c5Evaluator.isEntityReadPermitted(username, rootType)) {
            throw new RestReadException(RestReadOutcome.FORBIDDEN,
                "Access denied for entity: " + C5PermissionKeys.entityRead(rootType));
        }

        // 4. Р’Р°Р»РёРґРёСЂРѕРІР°С‚СЊ РїРѕР»СЏ Рё РѕРїРµСЂР°С†РёСЋ
        RestResourceProjection projection = resource.projection(RestReadOperation.LIST);
        List<String> effectiveAliases = determineEffectiveAliases(query.fields(), projection);

        // Р’Р°Р»РёРґР°С†РёСЏ paging СЃ СѓС‡С‘С‚РѕРј resource-specific maxPageSize
        if (query.size() < 0) {
            throw new RestReadException(RestReadOutcome.INVALID_REQUEST,
                "Page size must not be negative: " + query.size());
        }
        int effectivePageSize = query.size() == 0 ? resource.definition().defaultPageSize() : query.size();
        validatePaging(resource, query.page(), effectivePageSize);

        Pageable pageable = compilePageable(resource, query.page(), effectivePageSize,
            query.sortBy(), query.sortDirection());

        // 5. РџСЂРѕРІРµСЂРёС‚СЊ C5 РЅР° С„Р°РєС‚РёС‡РµСЃРєРё РёСЃРїРѕР»СЊР·СѓРµРјС‹Рµ Р°С‚СЂРёР±СѓС‚С‹
        authorizeAttributes(username, rootType, resource, effectiveAliases, query.filters(), pageable.getSort());

        // 6. РџСЂРёРјРµРЅРёС‚СЊ reference rules Рє Р°СЃСЃРѕС†РёР°С†РёСЏРј
        authorizeReferences(username, rootType, resource, effectiveAliases, query.filters(), pageable.getSort());

        // РџРѕР»СѓС‡РёС‚СЊ РїРѕР»РЅС‹Р№ fixed profile РёР· РєР°С‚Р°Р»РѕРіР°
        List<String> fixedFetchPaths = resource.fixedFetchPaths(RestReadOperation.LIST);

        // 7. РЎРєРѕРјРїРёР»РёСЂРѕРІР°С‚СЊ С„РёР»СЊС‚СЂС‹ Рё СЃРѕСЂС‚РёСЂРѕРІРєСѓ
        Specification<Object> spec = RestFilterSpecificationCompiler.compile(resource, query.filters());
        // Р’С‹РїРѕР»РЅРёС‚СЊ РєР°РЅРѕРЅРёС‡РµСЃРєРѕРµ С‡С‚РµРЅРёРµ РїРѕРґ RLS
        @SuppressWarnings("unchecked")
        Class<Object> targetType = (Class<Object>) rootType;
        Page<Object> pageResult;
        try {
            pageResult = readAccess.list(targetType, spec, pageable, fixedFetchPaths);
        } catch (C5AccessDeniedException denied) {
            throw new RestReadException(RestReadOutcome.FORBIDDEN,
                "Entity read permission was revoked before canonical list execution", denied);
        } catch (C5PermissionConfigurationException unavailable) {
            throw new RestReadException(RestReadOutcome.SECURITY_UNAVAILABLE,
                "Canonical C5 read enforcement is unavailable", unavailable);
        }

        // 8. РЎРєР°Р»СЏСЂРЅР°СЏ РїСЂРѕРµРєС†РёСЏ
        List<Map<String, Object>> projectedContent = new ArrayList<>();
        for (Object item : pageResult.getContent()) {
            projectedContent.add(RestScalarProjector.project(item, effectiveAliases, resource));
        }

        return new RestPageResult(projectedContent, pageResult.getNumber(), pageResult.getSize(),
            pageResult.getTotalElements(), pageResult.getTotalPages());
    }

    public RestDetailResult detail(RestDetailQuery query) {
        Objects.requireNonNull(query, "query must not be null");

        // 1. РџРѕР»СѓС‡РёС‚СЊ РїРѕРґС‚РІРµСЂР¶РґС‘РЅРЅС‹Р№ СЃСѓР±СЉРµРєС‚ (РѕС‚РєР°Р· РїСЂРё RLS bypass)
        String username = requireAuthenticatedUsername();

        // 2. РќР°Р№С‚Рё РѕРїСѓР±Р»РёРєРѕРІР°РЅРЅСѓСЋ РїР°СЂСѓ resource/major
        ResolvedRestResource resource = catalog.find(query.resource(), query.major())
            .orElseThrow(() -> new RestReadException(RestReadOutcome.RESOURCE_NOT_FOUND,
                "Resource not found: " + query.resource() + " v" + query.major()));

        // 3. РџСЂРѕРІРµСЂРёС‚СЊ API grant
        if (!c5Evaluator.isApiPermitted(username, query.resource(), query.major(), "DETAIL")) {
            throw new RestReadException(RestReadOutcome.FORBIDDEN,
                "Access denied for API operation: REST:" + query.resource() + ":v" + query.major() + ":DETAIL");
        }

        // РџСЂРѕРІРµСЂРёС‚СЊ C5 Entity Read РЅР° root
        Class<?> rootType = resource.definition().resourceType();
        if (!c5Evaluator.isEntityReadPermitted(username, rootType)) {
            throw new RestReadException(RestReadOutcome.FORBIDDEN,
                "Access denied for entity: " + C5PermissionKeys.entityRead(rootType));
        }

        // The internal contract receives typed ids. Parse text in the later HTTP adapter,
        // after authorization, and reject any coercion that could hide malformed input.
        Object typedId = validateDetailId(resource, query.id());

        // 4. Р’Р°Р»РёРґРёСЂРѕРІР°С‚СЊ РїРѕР»СЏ
        RestResourceProjection projection = resource.projection(RestReadOperation.DETAIL);
        List<String> effectiveAliases = determineEffectiveAliases(query.fields(), projection);

        // 5. РџСЂРѕРІРµСЂРёС‚СЊ C5 РЅР° Р°С‚СЂРёР±СѓС‚С‹
        authorizeAttributes(username, rootType, resource, effectiveAliases, Map.of(), null);

        // 6. РџСЂРёРјРµРЅРёС‚СЊ reference rules Рє Р°СЃСЃРѕС†РёР°С†РёСЏРј
        authorizeReferences(username, rootType, resource, effectiveAliases, Map.of(), Sort.unsorted());

        // РџРѕР»СѓС‡РёС‚СЊ РїРѕР»РЅС‹Р№ fixed profile
        List<String> fixedFetchPaths = resource.fixedFetchPaths(RestReadOperation.DETAIL);

        // 7. Р’С‹РїРѕР»РЅРёС‚СЊ С‡С‚РµРЅРёРµ
        @SuppressWarnings("unchecked")
        Class<Object> targetType = (Class<Object>) rootType;
        Optional<Object> entityOpt;
        try {
            entityOpt = readAccess.detail(targetType, typedId, fixedFetchPaths);
        } catch (C5AccessDeniedException denied) {
            throw new RestReadException(RestReadOutcome.FORBIDDEN,
                "Entity read permission was revoked before canonical detail execution", denied);
        } catch (C5PermissionConfigurationException unavailable) {
            throw new RestReadException(RestReadOutcome.SECURITY_UNAVAILABLE,
                "Canonical C5 read enforcement is unavailable", unavailable);
        }

        if (entityOpt.isEmpty()) {
            throw new RestReadException(RestReadOutcome.DETAIL_NOT_FOUND,
                "Entity not found for id: " + query.id());
        }

        // 8. РЎРєР°Р»СЏСЂРЅР°СЏ РїСЂРѕРµРєС†РёСЏ
        Map<String, Object> projected = RestScalarProjector.project(entityOpt.get(), effectiveAliases, resource);
        return new RestDetailResult(projected);
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
        if (username == null || username.isBlank() || "system".equalsIgnoreCase(username) || "anonymous".equalsIgnoreCase(username)) {
            throw new RestReadException(RestReadOutcome.UNAUTHENTICATED,
                "Authenticated subject is required for REST read");
        }
        return username;
    }

    private List<String> determineEffectiveAliases(List<String> requestedAliases, RestResourceProjection projection) {
        List<String> maximum = projection.maximumFields();
        List<String> defaults = projection.defaultFields();

        LinkedHashSet<String> chosen = new LinkedHashSet<>();
        if (maximum.contains("id")) {
            chosen.add("id");
        }

        if (requestedAliases == null || requestedAliases.isEmpty()) {
            chosen.addAll(defaults);
        } else {
            for (String alias : requestedAliases) {
                if (!maximum.contains(alias)) {
                    throw new RestReadException(RestReadOutcome.INVALID_REQUEST,
                        "Field '" + alias + "' is not declared in projection for operation " + projection.operation());
                }
                chosen.add(alias);
            }
        }

        return maximum.stream().filter(chosen::contains).toList();
    }

    private void validatePaging(ResolvedRestResource resource, int page, int size) {
        if (page < 0) {
            throw new RestReadException(RestReadOutcome.INVALID_REQUEST, "Page index must not be negative: " + page);
        }
        int effectiveMaxPageSize = Math.min(MAX_PAGE_SIZE, resource.definition().maxPageSize());
        if (size <= 0 || size > effectiveMaxPageSize) {
            throw new RestReadException(RestReadOutcome.INVALID_REQUEST,
                "Page size must be between 1 and " + effectiveMaxPageSize + "; requested: " + size);
        }
        long offset = (long) page * (long) size;
        if (offset > Integer.MAX_VALUE - size) {
            throw new RestReadException(RestReadOutcome.INVALID_REQUEST, "Page offset overflow");
        }
        long windowEnd = offset + size;
        if (windowEnd > maxWindow) {
            throw new RestReadException(RestReadOutcome.INVALID_REQUEST,
                "Page end exceeds the configured window: " + windowEnd + " > " + maxWindow);
        }
    }

    private Object validateDetailId(ResolvedRestResource resource, Object id) {
        ResolvedRestField idField = resource.fields().get("id");
        if (id == null || idField == null) {
            throw new RestReadException(RestReadOutcome.INVALID_REQUEST,
                "Detail id must be a typed value matching the published id field for " + resource.key());
        }
        Class<?> expected = boxed(idField.source().terminalType());
        if (!expected.isInstance(id)) {
            throw new RestReadException(RestReadOutcome.INVALID_REQUEST,
                "Invalid id type for resource " + resource.key() + ": expected "
                    + expected.getSimpleName() + ", got " + id.getClass().getSimpleName());
        }
        return id;
    }

    private Class<?> boxed(Class<?> type) {
        if (type == long.class) return Long.class;
        if (type == int.class) return Integer.class;
        if (type == boolean.class) return Boolean.class;
        if (type == short.class) return Short.class;
        return type;
    }

    private void authorizeAttributes(String username, Class<?> rootType, ResolvedRestResource resource,
                                     List<String> effectiveAliases, Map<String, Object> filters,
                                     Sort effectiveSort) {
        for (String alias : effectiveAliases) {
            ResolvedRestField field = resource.fields().get(alias);
            if (field != null) {
                String firstSegment = field.source().segments().getFirst().name();
                if (!c5Evaluator.isAttributeReadPermitted(username, rootType, firstSegment)) {
                    throw new RestReadException(RestReadOutcome.FORBIDDEN,
                        "Attribute read denied on root: " + firstSegment);
                }
            }
        }

        if (filters != null) {
            for (String filterName : filters.keySet()) {
                ResolvedRestPath path = resource.filters().get(filterName);
                if (path != null) {
                    String firstSegment = path.segments().getFirst().name();
                    if (!c5Evaluator.isAttributeReadPermitted(username, rootType, firstSegment)) {
                        throw new RestReadException(RestReadOutcome.FORBIDDEN,
                            "Attribute read denied on filter: " + firstSegment);
                    }
                }
            }
        }

        for (Sort.Order order : effectiveSort == null ? Sort.unsorted() : effectiveSort) {
            ResolvedRestPath path = resource.sorts().values().stream()
                .filter(candidate -> candidate.source().equals(order.getProperty()))
                .findFirst().orElse(null);
            if (path == null) {
                ResolvedRestField idField = resource.fields().get("id");
                if (idField != null && idField.source().source().equals(order.getProperty())) {
                    path = idField.source();
                }
            }
            if (path == null) {
                throw new RestReadException(RestReadOutcome.INVALID_REQUEST,
                    "Effective sort path is not present in the REST catalog: " + order.getProperty());
            }
            String persistentAttribute = path.segments().getFirst().name();
            if (!c5Evaluator.isAttributeReadPermitted(username, rootType, persistentAttribute)) {
                throw new RestReadException(RestReadOutcome.FORBIDDEN,
                    "Attribute read denied on sort: " + persistentAttribute);
            }
        }
    }

    private void authorizeReferences(String username, Class<?> rootType, ResolvedRestResource resource,
                                     List<String> effectiveAliases, Map<String, Object> filters,
                                     Sort effectiveSort) {
        for (String alias : effectiveAliases) {
            ResolvedRestField field = resource.fields().get(alias);
            if (field != null && field.source().segments().size() > 1) {
                if (!referenceValidator.authorizeReferencePath(username, rootType, field.source())) {
                    throw new RestReadException(RestReadOutcome.FORBIDDEN,
                        "Reference authorization denied for field: " + alias);
                }
            }
        }

        if (filters != null) {
            for (String filterName : filters.keySet()) {
                ResolvedRestPath path = resource.filters().get(filterName);
                if (path != null && path.segments().size() > 1) {
                    if (!referenceValidator.authorizeReferencePath(username, rootType, path)) {
                        throw new RestReadException(RestReadOutcome.FORBIDDEN,
                            "Reference authorization denied for filter: " + filterName);
                    }
                }
            }
        }

        for (Sort.Order order : effectiveSort == null ? Sort.unsorted() : effectiveSort) {
            ResolvedRestPath path = resource.sorts().values().stream()
                .filter(candidate -> candidate.source().equals(order.getProperty()))
                .findFirst().orElse(null);
            if (path == null) {
                ResolvedRestField idField = resource.fields().get("id");
                if (idField != null && idField.source().source().equals(order.getProperty())) {
                    path = idField.source();
                }
            }
            if (path != null && path.segments().size() > 1
                && !referenceValidator.authorizeReferencePath(username, rootType, path)) {
                throw new RestReadException(RestReadOutcome.FORBIDDEN,
                    "Reference authorization denied for sort: " + order.getProperty());
            }
        }
    }

    private Pageable compilePageable(ResolvedRestResource resource, int page, int size,
                                     String sortBy, RestSortDirection sortDirection) {
        String idPersistentPath = resource.fields().containsKey("id")
            ? resource.fields().get("id").source().source()
            : "id";

        Sort sort;
        if (sortBy != null && !sortBy.isBlank()) {
            ResolvedRestPath path = resource.sorts().get(sortBy);
            if (path == null) {
                throw new RestReadException(RestReadOutcome.INVALID_REQUEST,
                    "Invalid sort field: " + sortBy);
            }
            Sort.Direction direction = (sortDirection == RestSortDirection.DESC)
                ? Sort.Direction.DESC : Sort.Direction.ASC;
            sort = Sort.by(direction, path.source());
        } else if (resource.definition().defaultSort().isPresent()) {
            RestResourceDefinition.Sort defSort = resource.definition().defaultSort().get();
            ResolvedRestPath path = resource.sorts().get(defSort.field());
            if (path == null) {
                throw new RestReadException(RestReadOutcome.SECURITY_UNAVAILABLE,
                    "Default sort field '" + defSort.field() + "' is not present in the resolved REST catalog");
            }
            String sortProperty = path.source();
            Sort.Direction direction = (defSort.direction() == RestSortDirection.DESC)
                ? Sort.Direction.DESC : Sort.Direction.ASC;
            sort = Sort.by(direction, sortProperty);
        } else {
            sort = Sort.by(Sort.Direction.ASC, idPersistentPath);
        }

        if (sort.getOrderFor(idPersistentPath) == null) {
            sort = sort.and(Sort.by(Sort.Direction.ASC, idPersistentPath));
        }

        return PageRequest.of(page, size, sort);
    }
}
