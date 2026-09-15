package org.ipro.search;

import jakarta.persistence.QueryTimeoutException;
import org.ipro.data.CanonicalReadExecutor;
import org.ipro.data.SearchContext;
import org.ipro.data.SearchRead;
import org.ipro.rls.RlsContext;
import org.ipro.rls.RlsCurrentUser;
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
    private final RlsCurrentUser currentUser;

    /**
     * Установлен ли гейт аутентификации. {@code false} возможен только через
     * {@link #withoutSecurityContextForTests}: отсутствие security-контура не должно
     * отключать гейт само по себе.
     */
    private final boolean securityContextRequired;

    /**
     * Производственный конструктор: глобальный поиск — защищённая операция, поэтому
     * security-контур обязателен. Отсутствие {@link RlsCurrentUser} — ошибка wiring, а не
     * сигнал «гейт не нужен»: иначе поиск молча становился бы анонимным (fail-open), что
     * особенно опасно при разбиении платформы на модули, где меняется порядок и состав
     * авто-конфигураций.
     */
    public GlobalSearchService(GlobalSearchCatalog catalog,
                               GlobalSearchProviderRegistry providerRegistry,
                               CanonicalReadExecutor readExecutor,
                               RlsCurrentUser currentUser) {
        this(catalog, providerRegistry, readExecutor,
            Objects.requireNonNull(currentUser, "currentUser must not be null: глобальный поиск"
                + " не работает без security-контура; для срезов и unit-тестов используйте"
                + " withoutSecurityContextForTests(...)"), true);
    }

    /**
     * Явно названный незащищённый режим для hand-built срезов и unit-тестов без
     * security-контура. Отдельная фабрика вместо nullable collaborator'а: намерение видно в
     * месте вызова, и «небезопасный» экземпляр нельзя собрать случайно.
     */
    public static GlobalSearchService withoutSecurityContextForTests(
            GlobalSearchCatalog catalog,
            GlobalSearchProviderRegistry providerRegistry,
            CanonicalReadExecutor readExecutor) {
        return new GlobalSearchService(catalog, providerRegistry, readExecutor, null, false);
    }

    private GlobalSearchService(GlobalSearchCatalog catalog,
                                GlobalSearchProviderRegistry providerRegistry,
                                CanonicalReadExecutor readExecutor,
                                RlsCurrentUser currentUser,
                                boolean securityContextRequired) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.providerRegistry = Objects.requireNonNull(providerRegistry, "providerRegistry");
        this.readExecutor = Objects.requireNonNull(readExecutor, "readExecutor");
        this.currentUser = currentUser;
        this.securityContextRequired = securityContextRequired;
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
        requireAuthenticatedSubject();

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

    /**
     * C4.8: глобальный поиск — защищённая операция. До C4.5 сервис явно требовал
     * аутентифицированного субъекта ({@code requireAuthenticatedUsername}); в C4.5 эта
     * проверка была снята вместе с переходом на canonical boundary, и анонимный вызов
     * снова начал полагаться только на вертикальную RLS-фильтрацию и периметр роутов.
     *
     * <p>Теперь гейт восстановлен на границе сервиса: явный typed bypass ({@link RlsContext})
     * проходит как системная операция, а анонимный запрос отклоняется до обращения к
     * каталогу, провайдерам и SQL. Без security-контура сервис вообще не создаётся
     * (fail-closed на wiring): незащищённый экземпляр собирается только явной фабрикой
     * {@link #withoutSecurityContextForTests}.</p>
     */
    private void requireAuthenticatedSubject() {
        if (!securityContextRequired) {
            // Явно выбранный незащищённый режим среза/unit-теста, а не отсутствие wiring.
            return;
        }
        if (RlsContext.isBypassed()) {
            currentUser.username();
        } else {
            currentUser.requireAuthenticatedUsername();
        }
    }
}
