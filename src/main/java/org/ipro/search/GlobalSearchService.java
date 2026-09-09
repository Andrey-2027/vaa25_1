package org.ipro.search;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.QueryTimeoutException;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.rls.RlsReadGate;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Серверный MVP глобального поиска.
 *
 * <p>Сервис обходит только явно зарегистрированные источники каталога, а не все JPA-сущности.
 * Перед первым запросом включает RLS-фильтры; перед каждым источником применяет строгий
 * CHECK_ONLY read-гейт. Провайдеры получают уже ограниченный источник и не владеют обходом
 * безопасности или общими лимитами.</p>
 */
public class GlobalSearchService {

    private static final Logger log = LoggerFactory.getLogger(GlobalSearchService.class);

    /** Временной предел одного запроса источника; позже вынести в операционную конфигурацию. */
    public static final int QUERY_TIMEOUT_MS = 2_000;

    @PersistenceContext
    private EntityManager entityManager;

    private final GlobalSearchCatalog catalog;
    private final GlobalSearchProviderRegistry providerRegistry;
    private final RlsCurrentUser currentUser;
    private final RlsFilterActivator rlsFilterActivator;
    private final RlsReadGate rlsReadGate;

    public GlobalSearchService(GlobalSearchCatalog catalog,
                               GlobalSearchProviderRegistry providerRegistry,
                               RlsCurrentUser currentUser,
                               RlsFilterActivator rlsFilterActivator,
                               RlsReadGate rlsReadGate) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.providerRegistry = Objects.requireNonNull(providerRegistry, "providerRegistry");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser");
        this.rlsFilterActivator = Objects.requireNonNull(rlsFilterActivator, "rlsFilterActivator");
        this.rlsReadGate = Objects.requireNonNull(rlsReadGate, "rlsReadGate");
    }

    /** Выполнить поиск с безопасными лимитами по умолчанию. */
    @Transactional(readOnly = true)
    public GlobalSearchResponse search(String term) {
        return search(GlobalSearchRequest.of(term));
    }

    /** Выполнить bounded-поиск по явно зарегистрированным источникам. */
    @Transactional(readOnly = true)
    public GlobalSearchResponse search(GlobalSearchRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.isTooShort()) {
            return GlobalSearchResponse.tooShort(request.term());
        }

        String username = currentUser.username();
        // Как и LookupService, активируем фильтры до выполнения Criteria-запросов.
        rlsFilterActivator.ensureRlsEnabled(entityManager);

        List<GlobalSearchResult> results = new ArrayList<>();

        for (GlobalSearchSource source : catalog.sources()) {
            if (results.size() >= request.totalLimit()) {
                break;
            }
            if (!rlsReadGate.canRead(source.entityClass(), username)) {
                continue;
            }

            int sourceLimit = Math.min(
                request.perSourceLimit(), request.totalLimit() - results.size());
            if (sourceLimit <= 0) {
                break;
            }
            GlobalSearchProvider<Object> provider = providerRegistry.providerOf(source);
            List<Object> entities;
            try {
                entities = Objects.requireNonNull(provider.search(
                    entityManager, source, request.term(), sourceLimit, QUERY_TIMEOUT_MS),
                    "GlobalSearchProvider вернул null вместо списка результатов");
            } catch (QueryTimeoutException ex) {
                // Один тяжёлый источник не должен ронять весь глобальный поиск.
                // Остальные источники всё ещё могут вернуть полезные результаты.
                log.warn("Источник глобального поиска {} превысил лимит {} мс",
                    source.entityClass().getSimpleName(), QUERY_TIMEOUT_MS);
                continue;
            }

            int sourceResults = 0;
            for (Object entity : entities) {
                if (entity == null || sourceResults >= sourceLimit
                        || results.size() >= request.totalLimit()) {
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
                sourceResults++;
            }
        }

        boolean totalLimitReached = results.size() >= request.totalLimit();
        return new GlobalSearchResponse(request.term(), false, totalLimitReached, results);
    }
}
