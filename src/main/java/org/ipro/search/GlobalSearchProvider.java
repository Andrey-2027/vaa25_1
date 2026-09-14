package org.ipro.search;

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

    /** Classify a row already returned by the canonical ranked query. */
    GlobalSearchMatch classify(T entity, GlobalSearchSource source, String term);
}
