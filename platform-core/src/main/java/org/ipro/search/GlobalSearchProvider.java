package org.ipro.search;

import org.ipro.metadata.ColumnPath;

import java.util.Locale;

/**
 * Typed result customizer for one explicitly participating global-search entity.
 *
 * <p>Providers do not execute persistence queries. The canonical read executor owns the
 * bounded query, capability checks, RLS, fetch graph and telemetry; a provider supplies
 * only result mapping/classification and an optional query timeout.</p>
 */
public interface GlobalSearchProvider<T> {

    int DEFAULT_QUERY_TIMEOUT_MS = 2_000;

    /** Entity type this provider customizes. It must also declare {@link GlobalSearchable}. */
    Class<T> entityClass();

    /** Per-source timeout budget, applied by the canonical executor. */
    default int queryTimeoutMs() {
        return DEFAULT_QUERY_TIMEOUT_MS;
    }

    /** Extract the navigation identifier without returning an entity to the UI. */
    Object idOf(T entity);

    /** Produce the safe, already-loaded display value for a result. */
    String displayValue(T entity, GlobalSearchSource source);

    /**
     * Classify a row already returned by the canonical ranked query.
     *
     * <p>Стандартное правило — EXACT, затем PREFIX, затем SUBSTRING по загруженным поисковым
     * полям; раньше оно жило в дефолтном JPA-провайдере, и каждый кастомный провайдер был
     * обязан либо копировать его, либо делегировать implementation-классу. Теперь это
     * контракт по умолчанию: переопределять нужно только там, где сущность понимает «качество
     * совпадения» иначе.</p>
     */
    default GlobalSearchMatch classify(T entity, GlobalSearchSource source, String term) {
        String normalizedTerm = normalize(term);
        String firstSubstringField = null;
        String firstPrefixField = null;
        for (String fieldName : source.searchFields()) {
            Object value = ColumnPath.resolve(entityClass(), fieldName).getValue(entity);
            if (value == null) {
                continue;
            }
            String normalizedValue = normalize(String.valueOf(value));
            if (normalizedValue.equals(normalizedTerm)) {
                return new GlobalSearchMatch(GlobalSearchMatchKind.EXACT, fieldName);
            }
            if (firstPrefixField == null && normalizedValue.startsWith(normalizedTerm)) {
                firstPrefixField = fieldName;
            }
            if (firstSubstringField == null && normalizedValue.contains(normalizedTerm)) {
                firstSubstringField = fieldName;
            }
        }
        if (firstPrefixField != null) {
            return new GlobalSearchMatch(GlobalSearchMatchKind.PREFIX, firstPrefixField);
        }
        if (firstSubstringField != null) {
            return new GlobalSearchMatch(GlobalSearchMatchKind.SUBSTRING, firstSubstringField);
        }
        // The row was filtered by the canonical DB query; this is a defensive fallback.
        return new GlobalSearchMatch(GlobalSearchMatchKind.SUBSTRING,
            source.searchFields().get(0));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
