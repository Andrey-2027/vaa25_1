package org.ipro.search;

import jakarta.persistence.QueryTimeoutException;
import org.ipro.data.CanonicalReadExecutor;
import org.ipro.data.SearchContext;
import org.ipro.data.SearchRead;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Coordinates global limits and maps canonical query results into safe result DTOs. */
public class GlobalSearchService {

    private static final Logger log = LoggerFactory.getLogger(GlobalSearchService.class);

    /** Default per-source timeout; providers may declare a smaller or larger budget. */
    public static final int QUERY_TIMEOUT_MS = GlobalSearchProvider.DEFAULT_QUERY_TIMEOUT_MS;

    private final GlobalSearchCatalog catalog;
    private final GlobalSearchProviderRegistry providerRegistry;
    private final CanonicalReadExecutor readExecutor;

    public GlobalSearchService(GlobalSearchCatalog catalog,
                               GlobalSearchProviderRegistry providerRegistry,
                               CanonicalReadExecutor readExecutor) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.providerRegistry = Objects.requireNonNull(providerRegistry, "providerRegistry");
        this.readExecutor = Objects.requireNonNull(readExecutor, "readExecutor");
    }

    /** Execute search with safe default limits. */
    @Transactional(readOnly = true)
    public GlobalSearchResponse search(String term) {
        return search(GlobalSearchRequest.of(term));
    }

    /** Execute bounded search across explicitly opted-in sources. */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public GlobalSearchResponse search(GlobalSearchRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.isTooShort()) {
            return GlobalSearchResponse.tooShort(request.term());
        }

        List<GlobalSearchResult> results = new ArrayList<>();
        for (GlobalSearchSource source : catalog.sources()) {
            if (results.size() >= request.totalLimit()) {
                break;
            }
            int sourceLimit = Math.min(
                request.perSourceLimit(), request.totalLimit() - results.size());
            if (sourceLimit <= 0) {
                break;
            }

            GlobalSearchProvider<Object> provider =
                (GlobalSearchProvider<Object>) providerRegistry.providerOf(source);
            List<Object> entities;
            try {
                SearchRead<Object> searchRead = SearchRead.of(
                    (Class<Object>) source.entityClass(), SearchContext.GLOBAL,
                    request.term(), PageRequest.of(0, sourceLimit))
                    .withFields(source.searchFields())
                    .withPaths(source.additionalPaths());
                entities = readExecutor.readSearchWindow(searchRead, sourceLimit,
                    provider.queryTimeoutMs());
            } catch (QueryTimeoutException ex) {
                // One slow source must not prevent useful results from the remaining sources.
                log.warn("Источник глобального поиска {} превысил лимит {} мс",
                    source.entityClass().getSimpleName(), provider.queryTimeoutMs());
                continue;
            }

            for (Object entity : entities) {
                if (entity == null || results.size() >= request.totalLimit()) {
                    break;
                }
                GlobalSearchMatch match = Objects.requireNonNull(
                    provider.classify(entity, source, request.term()),
                    "GlobalSearchProvider вернул null вместо классификации совпадения");
                results.add(new GlobalSearchResult(
                    source.declarationOrder(),
                    source.groupTitle(),
                    source.entityClass(),
                    provider.idOf(entity),
                    provider.displayValue(entity, source),
                    match.kind(),
                    match.fieldName()));
            }
        }

        boolean totalLimitReached = results.size() >= request.totalLimit();
        return new GlobalSearchResponse(request.term(), false, totalLimitReached, results);
    }
}
