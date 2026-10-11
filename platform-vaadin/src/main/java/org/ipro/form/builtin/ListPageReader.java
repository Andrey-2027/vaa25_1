package org.ipro.form.builtin;

import org.ipro.filtergrid.filter.FilterNode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import java.util.Map;
import java.util.Collection;

/** Alternative canonical LIST intent; keeps standard grid paging, count and sorting. */
@FunctionalInterface
public interface ListPageReader<T> {
    Page<T> read(Specification<T> specification, FilterNode filters,
                 Map<String, Object> contextFilters, boolean opaqueContext,
                 Pageable pageable, Collection<String> fetchPaths);
}
