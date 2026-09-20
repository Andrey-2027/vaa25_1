package org.ipro.form.builtin;

import org.ipro.filtergrid.filter.FilterGroup;
import org.ipro.filtergrid.filter.FilterNode;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
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

    /**
     * Вырезает пустые группы из дерева условий; пустое дерево возвращает {@code null}.
     *
     * <p>Зачем: пустая группа — не фильтр, а под «ИЛИ» она компилируется в
     * {@code CriteriaBuilder.or()} без аргументов, то есть в «ложь» — грид показывал бы ноль
     * строк. Такие группы приходят снаружи: библиотечный {@code FilterTreeEditor.removeNode}
     * пересобирает группу, не проверяя, что она опустела, а у корневой группы нет кнопки
     * удаления — то есть после удаления последнего условия в дереве остаётся пустая группа,
     * из которой штатно не выйти (D3.5.2b). Группа с одним условием сохраняется как есть: её
     * схлопывание — уже про косметику группировки пользователя, а не про корректность.</p>
     */
    static FilterNode pruneEmptyGroups(FilterNode node) {
        if (!(node instanceof FilterGroup group)) {
            return node;
        }
        List<FilterNode> children = new ArrayList<>();
        for (FilterNode child : group.children()) {
            FilterNode pruned = pruneEmptyGroups(child);
            if (pruned != null) {
                children.add(pruned);
            }
        }
        return children.isEmpty() ? null : new FilterGroup(group.operator(), List.copyOf(children));
    }

    private void apply() {
        FilterNode root = new org.ipro.filtergrid.filter.FilterComposition(fixed, context, user).root();
        sink.accept(root == null ? null : compiler.apply(root), root);
    }
}
