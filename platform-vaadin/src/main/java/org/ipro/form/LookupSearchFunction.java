package org.ipro.form;

import java.util.List;
import java.util.Map;

/** A field's lookup dependencies travel with every search, including filtered autocomplete. */
@FunctionalInterface
public interface LookupSearchFunction<T> extends FilterableSearchFunction<T> {
    List<T> search(String term, Map<String, Object> filters, List<String> additionalPaths);

    @Override
    default List<T> search(String term, Map<String, Object> filters) {
        return search(term, filters, List.of());
    }
}
