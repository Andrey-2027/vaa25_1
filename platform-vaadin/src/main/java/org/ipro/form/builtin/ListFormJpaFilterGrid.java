package org.ipro.form.builtin;

import org.ipro.filtergrid.filter.FilterNode;
import org.ipro.filtergrid.jpa.JpaFilterGrid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import java.util.function.BiFunction;

/** Exposes the library's structured filter, without interpreting opaque JPA predicates. */
final class ListFormJpaFilterGrid<T> extends JpaFilterGrid<T> {
    ListFormJpaFilterGrid(Class<T> type, BiFunction<Specification<T>, Pageable, Page<T>> reader) {
        super(type, reader);
    }
    FilterNode filterTree() { return buildFilterTree(); }
}
