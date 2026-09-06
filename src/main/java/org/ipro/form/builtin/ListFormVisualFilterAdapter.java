package org.ipro.form.builtin;

import org.ipro.filtergrid.filter.FilterNode;
import org.springframework.data.jpa.domain.Specification;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Compatibility bridge for ListForm's legacy visual-filter state.
 * It deliberately does not replace GroupableJpaFilterGrid: the caller remains
 * responsible for composing the resulting specification with context filters.
 */
final class ListFormVisualFilterAdapter<T> {
    private final Function<FilterNode, Specification<T>> compiler;
    private final BiConsumer<Specification<T>, FilterNode> sink;
    private FilterNode fixed;
    private FilterNode context;
    private FilterNode user;

    ListFormVisualFilterAdapter(Function<FilterNode, Specification<T>> compiler,
                                BiConsumer<Specification<T>, FilterNode> sink) {
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    void setFixed(FilterNode value) { fixed = value; apply(); }
    void setContext(FilterNode value) { context = value; apply(); }
    void setUser(FilterNode value) { user = value; apply(); }
    void clearAll() {
        fixed = null;
        context = null;
        user = null;
        apply();
    }

    FilterNode fixed() { return fixed; }
    FilterNode context() { return context; }
    FilterNode user() { return user; }

    private void apply() {
        FilterNode root = new org.ipro.filtergrid.filter.FilterComposition(fixed, context, user).root();
        sink.accept(root == null ? null : compiler.apply(root), root);
    }
}
